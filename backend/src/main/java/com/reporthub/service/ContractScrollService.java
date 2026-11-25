package com.reporthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.common.BizException;
import com.reporthub.domain.ApiEndpointDef;
import com.reporthub.domain.ContractScrollBaseline;
import com.reporthub.domain.DataSourceDef;
import com.reporthub.domain.DatasetDef;
import com.reporthub.domain.SyncConfig;
import com.reporthub.engine.JsonPaths;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.engine.UpstreamClient;
import com.reporthub.repo.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;

/**
 * 《北方安防公司智能镀锌事业部销售合同滚动统计报表》核心引擎。
 *
 * 业务规则：
 * 1. 基线起点为 2026-10：10月期初取自 contract_scroll_baselines；
 * 2. 2026-10 及以后每月经营发生额 100% 取自 U9 实际业务单据：
 *    - T 黑件来料：BF003 客供原材料库 杂收单行（MiscRcvTransL.StoreUOMQty）
 *    - U 黑件出库：BF003 客供原材料库 生产领料单行（IssueDocLine.IssuedQty）
 *    - W 白件入库：BF004 镀锌成品库 完工报告（CompleteRpt.RcvQtyByWhUOM）
 *    - X 白件发货：BF004 镀锌成品库 出货单行（ShipLine.ShipQtyTUAmount）
 *    - Z 开票金额：镀锌应收单行（ARBillLine.AROCMoney.TotalMoney，已录发票号 InvoiceNum）
 *    - AA 回款金额：收款用途行（RecBillUseLine.Money.OCMoney）经核销单（ARApplyLine）正向追溯至应收单，未核销预收按客户欠款 FIFO 归属
 * 3. 月度滚动：M 月期初 = 2026-10 期初 + Σ(2026-10 .. M-1 系统发生额)，M 月期末 = M 月期初 + M 月系统发生额。
 * 4. 一合同多料品多单价：按「合同 + 料品行」独立核算，A–E 列按合同纵向合并，底部自动生成合计行。
 */
@Service
public class ContractScrollService {
    public static final String REPORT_CODE = "rpt_contract_scroll";
    public static final String BASELINE_MONTH = "2026-10";

    private static final List<String> REQUIRED_DATASETS = List.of(
            "ds_u9c_misc_rcv_l",
            "ds_u9c_issue_bf003",
            "ds_u9c_complete_bf004",
            "ds_u9c_ship_l",
            "ds_u9c_soline_galv",
            "ds_u9c_arbill_galv",
            "ds_u9c_arbill_misc",
            "ds_u9c_recbill_use",
            "ds_u9c_arapply_l"
    );

    private final ContractScrollBaselineRepo baselineRepo;
    private final SyncStore syncStore;
    private final SyncConfigRepo syncConfigRepo;
    private final DatasetRepo datasetRepo;
    private final ApiEndpointRepo endpointRepo;
    private final DataSourceRepo dataSourceRepo;
    private final UpstreamClient upstream;
    private final QueryModeService queryModeService;
    private final ReportResultCache resultCache;

    public ContractScrollService(ContractScrollBaselineRepo baselineRepo,
                                 SyncStore syncStore,
                                 SyncConfigRepo syncConfigRepo,
                                 DatasetRepo datasetRepo,
                                 ApiEndpointRepo endpointRepo,
                                 DataSourceRepo dataSourceRepo,
                                 UpstreamClient upstream,
                                 QueryModeService queryModeService,
                                 ReportResultCache resultCache) {
        this.baselineRepo = baselineRepo;
        this.syncStore = syncStore;
        this.syncConfigRepo = syncConfigRepo;
        this.datasetRepo = datasetRepo;
        this.endpointRepo = endpointRepo;
        this.dataSourceRepo = dataSourceRepo;
        this.upstream = upstream;
        this.queryModeService = queryModeService;
        this.resultCache = resultCache;
    }

    public record ScrollReportView(
            String month,
            String monthLabel,
            String dataMode,
            String lastSyncedAt,
            List<Map<String, Object>> columns,
            List<Map<String, Object>> groupHeaders,
            List<Map<String, Object>> rows,
            Map<String, Object> summaryRow,
            int page,
            int pageSize,
            long total,
            List<ReportQueryEngine.TraceCall> trace,
            long durationMs
    ) {}

    // ---------- 期初基线 CRUD ----------

    public List<ContractScrollBaseline> listBaselines() {
        return baselineRepo.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public ContractScrollBaseline saveBaseline(ContractScrollBaseline input) {
        if (input.getRowKind() == null || input.getRowKind().isBlank()) {
            input.setRowKind("contract");
        }
        if (input.getSortOrder() == null) {
            int maxSort = baselineRepo.findAllByOrderBySortOrderAscIdAsc().stream()
                    .mapToInt(b -> b.getSortOrder() == null ? 0 : b.getSortOrder())
                    .max().orElse(0);
            input.setSortOrder(maxSort + 10);
        }
        if (input.getOpenBlackStock() == null) input.setOpenBlackStock(0.0);
        if (input.getOpenWhiteStock() == null) input.setOpenWhiteStock(0.0);
        if (input.getOpenWhiteShipCum() == null) input.setOpenWhiteShipCum(0.0);
        if (input.getOpenInvoice() == null) input.setOpenInvoice(0.0);
        if (input.getOpenPayment() == null) input.setOpenPayment(0.0);
        if (input.getOpenBlackInCum() == null) input.setOpenBlackInCum(0.0);
        if (input.getOpenBlackOutCum() == null) input.setOpenBlackOutCum(0.0);
        if (input.getOpenWhiteInCum() == null) input.setOpenWhiteInCum(0.0);
        input.setUpdatedAt(Instant.now());
        ContractScrollBaseline saved = baselineRepo.save(input);
        resultCache.invalidateReport(REPORT_CODE);
        return saved;
    }

    @Transactional
    public void deleteBaseline(Long id) {
        baselineRepo.deleteById(id);
        resultCache.invalidateReport(REPORT_CODE);
    }

    // ---------- 核心查询入口 ----------

    public ReportQueryEngine.QueryResult queryAsEngineResult(Map<String, Object> filters, int page, int pageSize) {
        ScrollReportView view = computeView(filters, page, pageSize);
        return new ReportQueryEngine.QueryResult(
                view.columns(),
                view.rows(),
                view.page(),
                view.pageSize(),
                view.total(),
                view.trace(),
                view.durationMs()
        );
    }

    public ScrollReportView computeView(Map<String, Object> filters, int page, int pageSize) {
        long t0 = System.currentTimeMillis();
        String targetMonth = extractTargetMonth(filters);
        if (targetMonth.compareTo(BASELINE_MONTH) < 0) {
            targetMonth = BASELINE_MONTH;
        }
        String monthLabel = formatMonthLabel(targetMonth);
        String mode = queryModeService.mode();
        List<ReportQueryEngine.TraceCall> trace = Collections.synchronizedList(new ArrayList<>());

        // 按「月份 + 数据模式」复用 ReportResultCache：实时模式下查询后立刻导出或按客户/合同二次筛选可直接秒开
        final String tm = targetMonth;
        String cacheKey = resultCache.key(REPORT_CODE, Map.of("month", tm), mode);
        var cachedHit = resultCache.getFresh(cacheKey);
        List<Map<String, Object>> allProjected;
        List<Map<String, Object>> cols;
        if (cachedHit != null) {
            allProjected = cachedHit.rows();
            cols = cachedHit.columns();
            trace.add(new ReportQueryEngine.TraceCall("cache", "result-cache", "hit:" + tm, allProjected.size(), 0));
        } else {
            var loaded = resultCache.getOrLoad(cacheKey, () -> {
                Map<String, List<ObjectNode>> rawByDs = loadDatasets(trace);
                List<WorkingRow> workingRows = buildWorkingRows(tm, rawByDs);
                List<String> months = enumerateMonths(BASELINE_MONTH, tm);
                accumulateUpstreamFlows(workingRows, months, rawByDs);
                List<Map<String, Object>> projected = new ArrayList<>(workingRows.size());
                for (WorkingRow wr : workingRows) {
                    projected.add(projectRow(wr, tm, months));
                }
                return new ReportResultCache.CachedResult(
                        buildColumns(tm), projected, projected.size(), System.currentTimeMillis());
            });
            allProjected = loaded.rows();
            cols = loaded.columns();
        }

        List<Map<String, Object>> filtered = applyRowFilters(allProjected, filters);
        Map<String, Object> summaryRow = buildSummaryRow(filtered);

        int p = Math.max(1, page);
        int size = pageSize <= 0 ? 200 : pageSize;
        List<Map<String, Object>> sliced;
        if (size >= filtered.size() && p == 1) {
            sliced = filtered;
        } else {
            int from = (p - 1) * size;
            sliced = from >= filtered.size() ? List.of() : filtered.subList(from, Math.min(filtered.size(), from + size));
        }

        List<Map<String, Object>> groups = buildGroupHeaders(targetMonth);
        String lastSynced = QueryModeService.LOCAL.equals(mode) ? resolveLastSyncedAt() : null;

        return new ScrollReportView(
                targetMonth,
                monthLabel,
                mode,
                lastSynced,
                cols,
                groups,
                sliced,
                summaryRow,
                p,
                size,
                filtered.size(),
                new ArrayList<>(trace),
                System.currentTimeMillis() - t0
        );
    }

    private String resolveLastSyncedAt() {
        Instant latest = null;
        for (String ds : REQUIRED_DATASETS) {
            Instant ts = syncStore.lastSyncedAt(ds);
            if (ts != null && (latest == null || ts.isAfter(latest))) {
                latest = ts;
            }
        }
        return latest == null ? null : latest.toString();
    }

    /**
     * 首页经营驾驶舱聚合视图（基于滚动报表真实数据，自动复用 ReportResultCache）。
     */
    public Map<String, Object> computeExecutiveOverview(String requestedMonth) {
        String nowYm = YearMonth.now().toString();
        String latestMonth = nowYm.compareTo(BASELINE_MONTH) < 0 ? BASELINE_MONTH : nowYm;
        List<String> allMonths = enumerateMonths(BASELINE_MONTH, latestMonth);
        List<String> availableMonths = new ArrayList<>(allMonths);
        Collections.reverse(availableMonths);

        String targetMonth = (requestedMonth != null && requestedMonth.matches("\\d{4}-\\d{2}")
                && requestedMonth.compareTo(BASELINE_MONTH) >= 0
                && requestedMonth.compareTo(latestMonth) <= 0)
                ? requestedMonth : latestMonth;

        String prevMonth = targetMonth.compareTo(BASELINE_MONTH) > 0
                ? YearMonth.parse(targetMonth).minusMonths(1).toString() : null;

        ScrollReportView curView = computeView(Map.of("month", targetMonth), 1, 10000);
        ScrollReportView prevView = prevMonth != null ? computeView(Map.of("month", prevMonth), 1, 10000) : null;

        Map<String, Object> curSum = curView.summaryRow() != null ? curView.summaryRow() : Map.of();
        Map<String, Object> prevSum = (prevView != null && prevView.summaryRow() != null) ? prevView.summaryRow() : Map.of();

        double curWhiteShip = numOf(curSum.get("cur_white_ship"));
        double prevWhiteShip = numOf(prevSum.get("cur_white_ship"));
        double curWhiteIn = numOf(curSum.get("cur_white_in"));

        double curInv = numOf(curSum.get("cur_inv_amt"));
        double prevInv = numOf(prevSum.get("cur_inv_amt"));
        double endInvCum = numOf(curSum.get("end_inv_cum"));

        double curPay = numOf(curSum.get("cur_pay_amt"));
        double prevPay = numOf(prevSum.get("cur_pay_amt"));

        double endDebt = numOf(curSum.get("end_total_debt"));
        double prevDebt = prevMonth != null ? numOf(prevSum.get("end_total_debt")) : numOf(curSum.get("open_total_debt"));
        double endInvUnpaid = numOf(curSum.get("end_inv_unpaid_amt"));

        double endBlackStock = numOf(curSum.get("end_black_stock"));
        double endWhiteStock = numOf(curSum.get("end_white_stock"));
        double endStockTotal = round4(endBlackStock + endWhiteStock);
        double prevStockTotal = prevMonth != null
                ? round4(numOf(prevSum.get("end_black_stock")) + numOf(prevSum.get("end_white_stock")))
                : round4(numOf(curSum.get("open_black_stock")) + numOf(curSum.get("open_white_stock")));

        String baseDrill = "/reports/" + REPORT_CODE + "?month=" + targetMonth;
        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpiItem("white_ship", "当月白件发货", round2(curWhiteShip), prevMonth != null ? round2(prevWhiteShip) : null,
                "吨", "ton", true, "当月白件入库 " + fmtNum(curWhiteIn) + " 吨", baseDrill));
        kpis.add(kpiItem("invoice_amt", "当月开票金额", round2(curInv), prevMonth != null ? round2(prevInv) : null,
                "元", "money", true, "累计开票 " + fmtWan(endInvCum), baseDrill));
        kpis.add(kpiItem("payment_amt", "当月回款金额", round2(curPay), prevMonth != null ? round2(prevPay) : null,
                "元", "money", true,
                curInv > 0.01 ? "当月回款/开票比 " + Math.round((curPay / curInv) * 100) + "%" : "累计回款 " + fmtWan(numOf(curSum.get("end_pay_cum"))),
                baseDrill));
        kpis.add(kpiItem("total_debt", "期末总欠款", round2(endDebt), round2(prevDebt),
                "元", "money", false, "已开票未回款 " + fmtWan(endInvUnpaid), baseDrill));
        kpis.add(kpiItem("wip_stock", "期末在制库存", round2(endStockTotal), round2(prevStockTotal),
                "吨", "ton", null,
                "黑件 " + fmtNum(endBlackStock) + " 吨 · 白件 " + fmtNum(endWhiteStock) + " 吨", baseDrill));

        // 趋势序列：最多近 6 个月（按时间正序）
        List<String> seriesMonths = allMonths.size() > 6 ? allMonths.subList(allMonths.size() - 6, allMonths.size()) : allMonths;
        List<Map<String, Object>> monthlySeries = new ArrayList<>(seriesMonths.size());
        for (String ym : seriesMonths) {
            ScrollReportView v = ym.equals(targetMonth) ? curView
                    : (ym.equals(prevMonth) && prevView != null ? prevView : computeView(Map.of("month", ym), 1, 10000));
            Map<String, Object> s = v.summaryRow() != null ? v.summaryRow() : Map.of();
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("month", ym);
            pt.put("label", Integer.parseInt(ym.substring(5)) + "月");
            pt.put("blackIn", round2(numOf(s.get("cur_black_in"))));
            pt.put("blackOut", round2(numOf(s.get("cur_black_out"))));
            pt.put("whiteIn", round2(numOf(s.get("cur_white_in"))));
            pt.put("whiteShip", round2(numOf(s.get("cur_white_ship"))));
            pt.put("invoiceAmt", round2(numOf(s.get("cur_inv_amt"))));
            pt.put("paymentAmt", round2(numOf(s.get("cur_pay_amt"))));
            pt.put("totalDebt", round2(numOf(s.get("end_total_debt"))));
            monthlySeries.add(pt);
        }

        // 客户欠款 Top 8（合并同客户多合同）
        Map<String, double[]> custAgg = new LinkedHashMap<>();
        Map<String, Set<String>> custContracts = new HashMap<>();
        for (Map<String, Object> row : curView.rows()) {
            String cust = String.valueOf(row.getOrDefault("customer_name", "")).trim();
            if (cust.isEmpty()) continue;
            double[] acc = custAgg.computeIfAbsent(cust, k -> new double[3]);
            acc[0] += numOf(row.get("end_total_debt"));
            acc[1] += numOf(row.get("end_inv_unpaid_amt"));
            acc[2] += numOf(row.get("end_ship_uninv_amt"));
            String cno = String.valueOf(row.getOrDefault("contract_no", "")).trim();
            if (!cno.isEmpty()) {
                custContracts.computeIfAbsent(cust, k -> new HashSet<>()).add(cno);
            }
        }
        double positiveDebtSum = custAgg.values().stream().mapToDouble(a -> Math.max(0, a[0])).sum();
        List<Map<String, Object>> topDebtCustomers = custAgg.entrySet().stream()
                .filter(e -> e.getValue()[0] > 0.01)
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(8)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customerName", e.getKey());
                    m.put("totalDebt", round2(e.getValue()[0]));
                    m.put("invUnpaid", round2(e.getValue()[1]));
                    m.put("shipUninv", round2(e.getValue()[2]));
                    m.put("contractCount", custContracts.getOrDefault(e.getKey(), Set.of()).size());
                    m.put("sharePct", positiveDebtSum > 0.01 ? round2(e.getValue()[0] * 100.0 / positiveDebtSum) : 0.0);
                    return m;
                }).toList();

        // 负库存预警 Top 8
        List<Map<String, Object>> stockAlerts = curView.rows().stream()
                .filter(row -> numOf(row.get("end_black_stock")) < -0.001 || numOf(row.get("end_white_stock")) < -0.001)
                .sorted(Comparator.comparingDouble(row -> Math.min(numOf(row.get("end_black_stock")), numOf(row.get("end_white_stock")))))
                .limit(8)
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("contractNo", String.valueOf(row.getOrDefault("contract_no", "")));
                    m.put("customerName", String.valueOf(row.getOrDefault("customer_name", "")));
                    m.put("productName", String.valueOf(row.getOrDefault("product_name", "")));
                    m.put("blackStock", round2(numOf(row.get("end_black_stock"))));
                    m.put("whiteStock", round2(numOf(row.get("end_white_stock"))));
                    return m;
                }).toList();

        long totalNegativeCount = curView.rows().stream()
                .filter(row -> numOf(row.get("end_black_stock")) < -0.001 || numOf(row.get("end_white_stock")) < -0.001)
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", targetMonth);
        out.put("monthLabel", curView.monthLabel());
        out.put("prevMonth", prevMonth);
        out.put("availableMonths", availableMonths);
        out.put("contractCount", curView.total());
        out.put("kpis", kpis);
        out.put("monthlySeries", monthlySeries);
        out.put("topDebtCustomers", topDebtCustomers);
        out.put("stockAlerts", stockAlerts);
        out.put("stockAlertTotal", totalNegativeCount);
        out.put("lastSyncedAt", curView.lastSyncedAt());
        return out;
    }

    private Map<String, Object> kpiItem(String key, String label, double value, Double prevValue,
                                        String unit, String kind, Boolean positiveIsGood,
                                        String sub, String drillUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("prevValue", prevValue);
        Double deltaPct = null;
        if (prevValue != null && Math.abs(prevValue) > 0.01) {
            deltaPct = round2((value - prevValue) * 100.0 / Math.abs(prevValue));
        }
        m.put("deltaPct", deltaPct);
        m.put("unit", unit);
        m.put("kind", kind);
        m.put("positiveIsGood", positiveIsGood);
        m.put("sub", sub);
        m.put("drillUrl", drillUrl);
        return m;
    }

    private static double numOf(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String fmtNum(double v) {
        return String.format(java.util.Locale.CHINA, "%,.2f", v);
    }

    private static String fmtWan(double v) {
        if (Math.abs(v) >= 10000.0) {
            return String.format(java.util.Locale.CHINA, "%,.2f 万", v / 10000.0);
        }
        return String.format(java.util.Locale.CHINA, "%,.2f 元", v);
    }

    // ---------- 内部行与月度桶模型 ----------

    private static final class MonthBucket {
        double blackIn;     // T
        double blackOut;    // U
        double whiteIn;     // W
        double whiteShip;   // X
        double invAmt;      // Z (已开票)
        double arAccrued;   // 应收单含税总额（含未录发票号）
        double payAmt;      // AA
    }

    private static final class WorkingRow {
        String key;
        String rowKind; // pre2026 | contract | uncontracted
        Integer seqNo;
        String mergeGroup;
        String contractNo;
        Set<String> projectCodes = new LinkedHashSet<>();
        String customerName;
        String normCust;
        String signDate;
        String salesman;
        String productName;
        String itemCode;
        String itemSuffix;
        Double unitPrice;
        Double estTonnage;
        Double estAmount;
        Double settleAmount;
        double openBlackStock;
        double openWhiteStock;
        double openWhiteShipCum;
        double openInvoice;
        double openPayment;
        double openBlackInCum;
        double openBlackOutCum;
        double openWhiteInCum;
        double preOctArAccrued;
        String remark;
        Map<String, MonthBucket> monthly = new HashMap<>();

        MonthBucket bucket(String ym) {
            return monthly.computeIfAbsent(ym, k -> new MonthBucket());
        }
    }

    private List<WorkingRow> buildWorkingRows(String targetMonth, Map<String, List<ObjectNode>> rawByDs) {
        List<ContractScrollBaseline> seeds = baselineRepo.findAllByOrderBySortOrderAscIdAsc();
        List<WorkingRow> pre2026 = new ArrayList<>();
        List<WorkingRow> contracts = new ArrayList<>();
        List<WorkingRow> uncontracted = new ArrayList<>();
        Set<String> claimedProjCodes = new HashSet<>();

        int maxContractSeq = 0;
        for (ContractScrollBaseline b : seeds) {
            WorkingRow r = new WorkingRow();
            r.key = "b-" + b.getId();
            r.rowKind = b.getRowKind() == null ? "contract" : b.getRowKind();
            r.seqNo = b.getSeqNo();
            r.contractNo = nvl(b.getContractNo());
            r.customerName = nvl(b.getCustomerName());
            r.normCust = normCust(r.customerName);
            r.signDate = nvl(b.getSignDate());
            r.salesman = nvl(b.getSalesman());
            r.productName = nvl(b.getProductName());
            r.itemCode = nvl(b.getItemCode());
            r.itemSuffix = normItemSuffix(r.itemCode);
            r.unitPrice = b.getUnitPrice();
            r.estTonnage = b.getEstTonnage();
            r.estAmount = b.getEstAmount() != null ? b.getEstAmount()
                    : (b.getUnitPrice() != null && b.getEstTonnage() != null ? round2(b.getUnitPrice() * b.getEstTonnage()) : null);
            r.settleAmount = b.getSettleAmount();
            r.openBlackStock = nvlNum(b.getOpenBlackStock());
            r.openWhiteStock = nvlNum(b.getOpenWhiteStock());
            r.openWhiteShipCum = nvlNum(b.getOpenWhiteShipCum());
            r.openInvoice = nvlNum(b.getOpenInvoice());
            r.openPayment = nvlNum(b.getOpenPayment());
            r.openBlackInCum = nvlNum(b.getOpenBlackInCum());
            r.openBlackOutCum = nvlNum(b.getOpenBlackOutCum());
            r.openWhiteInCum = nvlNum(b.getOpenWhiteInCum());
            r.remark = nvl(b.getRemark());

            if (!r.contractNo.isBlank() && !"2026年以前合同".equals(r.contractNo)) {
                r.projectCodes.add(r.contractNo);
                claimedProjCodes.add(r.contractNo);
            }
            if (b.getU9ProjectCodes() != null && !b.getU9ProjectCodes().isBlank()) {
                for (String p : b.getU9ProjectCodes().split("[,，;\\s]+")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        r.projectCodes.add(t);
                        claimedProjCodes.add(t);
                    }
                }
            }
            if ("pre2026".equals(r.rowKind)) {
                r.mergeGroup = "PRE2026";
                pre2026.add(r);
            } else if ("uncontracted".equals(r.rowKind)) {
                r.mergeGroup = "UN-" + (r.seqNo != null ? r.seqNo : r.key);
                uncontracted.add(r);
            } else {
                r.mergeGroup = "C-" + (r.seqNo != null ? r.seqNo : r.contractNo);
                if (r.seqNo != null && r.seqNo > maxContractSeq) maxContractSeq = r.seqNo;
                contracts.add(r);
            }
        }

        // 自动发现 U9 新增镀锌销售订单行（2026-11 起新签订单，或 2026-10 有实际镀锌出入库但未入基线的订单）
        Set<String> activeWhProjects = collectActiveWhProjects(rawByDs);
        List<ObjectNode> solines = new ArrayList<>(rawByDs.getOrDefault("ds_u9c_soline_galv", List.of()));
        solines.sort(Comparator
                .comparing((ObjectNode n) -> strAt(n, "SO.BusinessDate"))
                .thenComparing(n -> strAt(n, "SO.DocNo"))
                .thenComparingInt(n -> intAt(n, "DocLineNo")));

        Map<String, Integer> newOrderSeqMap = new LinkedHashMap<>();
        for (ObjectNode soLine : solines) {
            String soDocNo = strAt(soLine, "SO.DocNo");
            String projCode = strAt(soLine, "Project.Code");
            if (claimedProjCodes.contains(soDocNo) || (!projCode.isEmpty() && claimedProjCodes.contains(projCode))) {
                continue;
            }
            String bdate = date10(strAt(soLine, "SO.BusinessDate"));
            if (bdate.length() < 7) continue;
            String ym = bdate.substring(0, 7);
            if (ym.compareTo(targetMonth) > 0) continue;
            // 2026-10 之前或 2026-10 当月已被人工排除在基线外的非镀锌加工单（如带钢销售/机加工件），无镀锌出入库则不混入
            if (ym.compareTo("2026-11") < 0
                    && !activeWhProjects.contains(soDocNo)
                    && (projCode.isEmpty() || !activeWhProjects.contains(projCode))) {
                continue;
            }

            String displayContract = !projCode.isEmpty() ? projCode : soDocNo;
            Integer seq = newOrderSeqMap.get(displayContract);
            if (seq == null) {
                seq = ++maxContractSeq;
                newOrderSeqMap.put(displayContract, seq);
            }
            WorkingRow nr = new WorkingRow();
            nr.key = "so-" + strAt(soLine, "ID");
            nr.rowKind = "contract";
            nr.seqNo = seq;
            nr.mergeGroup = "C-" + seq;
            nr.contractNo = displayContract;
            if (!soDocNo.isEmpty()) nr.projectCodes.add(soDocNo);
            if (!projCode.isEmpty()) nr.projectCodes.add(projCode);
            nr.customerName = strAt(soLine, "SO.OrderBy.Name");
            nr.normCust = normCust(nr.customerName);
            nr.signDate = bdate;
            nr.salesman = strAt(soLine, "SO.Seller.Name");
            nr.itemCode = strAt(soLine, "ItemInfo.ItemCode");
            nr.itemSuffix = normItemSuffix(nr.itemCode);
            nr.productName = cleanProductName(strAt(soLine, "ItemInfo.ItemName"));
            nr.unitPrice = numAt(soLine, "FinallyPriceTC");
            nr.estTonnage = numAt(soLine, "OrderByQtyTU");
            Double totalTc = numAt(soLine, "TotalMoneyTC");
            nr.estAmount = totalTc != null ? round2(totalTc)
                    : (nr.unitPrice != null && nr.estTonnage != null ? round2(nr.unitPrice * nr.estTonnage) : null);
            nr.remark = "";
            contracts.add(nr);
        }

        List<WorkingRow> all = new ArrayList<>(pre2026.size() + contracts.size() + uncontracted.size() + 8);
        all.addAll(pre2026);
        all.addAll(contracts);
        all.addAll(uncontracted);
        return all;
    }

    private Set<String> collectActiveWhProjects(Map<String, List<ObjectNode>> rawByDs) {
        Set<String> set = new HashSet<>();
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_complete_bf004", List.of())) {
            if (month7(strAt(n, "BusinessDate")).compareTo(BASELINE_MONTH) < 0) continue;
            addNonBlank(set, strAt(n, "Project.Code"));
            addNonBlank(set, strAt(n, "MO.Project.Code"));
        }
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_issue_bf003", List.of())) {
            if (month7(strAt(n, "IssueDoc.BusinessDate")).compareTo(BASELINE_MONTH) < 0) continue;
            addNonBlank(set, strAt(n, "Project.Code"));
            addNonBlank(set, strAt(n, "MO.Project.Code"));
        }
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_ship_l", List.of())) {
            if (month7(strAt(n, "Ship.BusinessDate")).compareTo(BASELINE_MONTH) < 0) continue;
            addNonBlank(set, strAt(n, "Project.Code"));
        }
        return set;
    }

    private void accumulateUpstreamFlows(List<WorkingRow> rows,
                                         List<String> months,
                                         Map<String, List<ObjectNode>> rawByDs) {
        Set<String> monthSet = new HashSet<>(months);
        String maxMonth = months.get(months.size() - 1);

        // 索引：projectCode -> rows
        Map<String, List<WorkingRow>> byProj = new HashMap<>();
        Map<String, WorkingRow> uncontractedByCust = new HashMap<>();
        int maxUncontractedSeq = 132;
        for (WorkingRow r : rows) {
            for (String pc : r.projectCodes) {
                byProj.computeIfAbsent(pc, k -> new ArrayList<>()).add(r);
            }
            if ("uncontracted".equals(r.rowKind)) {
                if (!r.normCust.isEmpty()) uncontractedByCust.put(r.normCust, r);
                if (r.seqNo != null && r.seqNo > maxUncontractedSeq) maxUncontractedSeq = r.seqNo;
            }
        }
        final int[] nextUnSeq = {maxUncontractedSeq};

        java.util.function.Function<String, WorkingRow> getOrCreateUncontracted = (String rawCust) -> {
            String nc = normCust(rawCust);
            if (nc.isEmpty()) nc = "散户";
            WorkingRow hit = findByCustAlias(uncontractedByCust, nc);
            if (hit != null) return hit;
            WorkingRow u = new WorkingRow();
            u.key = "un-dyn-" + (++nextUnSeq[0]);
            u.rowKind = "uncontracted";
            u.seqNo = nextUnSeq[0];
            u.mergeGroup = "UN-" + u.seqNo;
            u.contractNo = "";
            u.customerName = rawCust == null || rawCust.isBlank() ? nc : rawCust.trim();
            u.normCust = nc;
            u.signDate = "";
            u.salesman = "";
            u.productName = "";
            u.itemCode = "";
            u.itemSuffix = "";
            u.remark = "";
            rows.add(u);
            uncontractedByCust.put(nc, u);
            return u;
        };

        // 1. 黑件出库 U (ds_u9c_issue_bf003)
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_issue_bf003", List.of())) {
            String ym = month7(strAt(n, "IssueDoc.BusinessDate"));
            if (!monthSet.contains(ym)) continue;
            Double qtyObj = numAt(n, "IssuedQty");
            if (qtyObj == null) qtyObj = numAt(n, "IssueQty");
            double qty = qtyObj == null ? 0.0 : qtyObj;
            if (Math.abs(qty) < 1e-9) continue;

            String proj = firstNonBlank(strAt(n, "MO.Project.Code"), strAt(n, "Project.Code"));
            String itemCode = strAt(n, "Item.Code");
            String itemName = strAt(n, "Item.Name");
            String custCat = strAt(n, "Item.MainItemCategory.Name");
            WorkingRow target = matchOperationalRow(rows, byProj, proj, itemCode, itemName, custCat, ym, getOrCreateUncontracted);
            if (target != null) target.bucket(ym).blackOut += qty;
        }

        // 2. 白件入库 W (ds_u9c_complete_bf004)
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_complete_bf004", List.of())) {
            String ym = month7(strAt(n, "BusinessDate"));
            if (!monthSet.contains(ym)) continue;
            Double qtyObj = numAt(n, "RcvQtyByWhUOM");
            if (qtyObj == null) qtyObj = numAt(n, "CompleteQty");
            double qty = qtyObj == null ? 0.0 : qtyObj;
            if (Math.abs(qty) < 1e-9) continue;

            String proj = firstNonBlank(strAt(n, "Project.Code"), strAt(n, "MO.Project.Code"));
            String itemCode = strAt(n, "Item.Code");
            String itemName = strAt(n, "Item.Name");
            String custCat = strAt(n, "Item.MainItemCategory.Name");
            WorkingRow target = matchOperationalRow(rows, byProj, proj, itemCode, itemName, custCat, ym, getOrCreateUncontracted);
            if (target != null) target.bucket(ym).whiteIn += qty;
        }

        // 3. 白件发货 X (ds_u9c_ship_l)，同时记录 Ship.DocNo -> WorkingRow 供 ARBillLine.SrcBillNum 溯源
        Map<String, WorkingRow> shipDocItemRow = new HashMap<>();
        Map<String, WorkingRow> shipDocRow = new HashMap<>();
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_ship_l", List.of())) {
            String ym = month7(strAt(n, "Ship.BusinessDate"));
            String shipDoc = strAt(n, "Ship.DocNo");
            String proj = strAt(n, "Project.Code");
            String itemCode = strAt(n, "ItemInfo.ItemCode");
            String itemName = strAt(n, "ItemInfo.ItemName");
            String custCat = strAt(n, "ItemInfo.ItemID.MainItemCategory.Name");
            String suffix = normItemSuffix(itemCode);

            boolean inRange = monthSet.contains(ym);
            Double qtyObj = numAt(n, "ShipQtyTUAmount");
            double qty = qtyObj == null ? 0.0 : qtyObj;
            // 仅统计区间内且有实际发货量时才允许动态新增无合同散户行，避免 10 月前旧单产生零发生散户行
            java.util.function.Function<String, WorkingRow> factory =
                    (inRange && Math.abs(qty) >= 1e-9)
                            ? getOrCreateUncontracted
                            : (c -> findByCustAlias(uncontractedByCust, normCust(c)));

            WorkingRow matched = matchOperationalRow(rows, byProj, proj, itemCode, itemName, custCat,
                    inRange ? ym : BASELINE_MONTH, factory);
            if (matched != null && !shipDoc.isEmpty()) {
                shipDocRow.putIfAbsent(shipDoc, matched);
                if (!suffix.isEmpty()) shipDocItemRow.put(shipDoc + "#" + suffix, matched);
            }
            if (!inRange || Math.abs(qty) < 1e-9) continue;
            if (matched != null) matched.bucket(ym).whiteShip += qty;
        }

        // 4. 黑件来料 T (ds_u9c_misc_rcv_l)：杂收单无项目号时按同客户+同物料族当月领料U / 生产完工W+发货X 比例精准归属
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_misc_rcv_l", List.of())) {
            String ym = month7(strAt(n, "MiscRcvTrans.BusinessDate"));
            if (!monthSet.contains(ym)) continue;
            Double qtyObj = numAt(n, "StoreUOMQty");
            double qty = qtyObj == null ? 0.0 : qtyObj;
            if (Math.abs(qty) < 1e-9) continue;

            String proj = strAt(n, "Project.Code");
            String itemCode = strAt(n, "ItemInfo.ItemCode");
            String itemName = strAt(n, "ItemInfo.ItemName");
            String custCat = strAt(n, "ItemInfo.ItemID.MainItemCategory.Name");

            if (!proj.isEmpty() && byProj.containsKey(proj)) {
                WorkingRow direct = pickBestItemRow(byProj.get(proj), normItemSuffix(itemCode), itemName);
                if (direct != null) {
                    direct.bucket(ym).blackIn += qty;
                    continue;
                }
            }
            distributeBlackIncoming(rows, ym, qty, itemCode, itemName, custCat, getOrCreateUncontracted);
        }

        // 5. 应收/开票 Z (ds_u9c_arbill_galv) + 建立 ARBillHead.DocNo -> 行分摊索引供回款核销正向溯源
        record ArLineSlice(WorkingRow row, double totalMoney, double paidMoney) {}
        Map<String, List<ArLineSlice>> arDocSlices = new HashMap<>();

        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_arbill_galv", List.of())) {
            String bdate = strAt(n, "ARBillHead.BusinessDate");
            String ym = month7(bdate);
            if (ym.isEmpty() || ym.compareTo(maxMonth) > 0) continue;

            String srcType = strAt(n, "ARBillHead.SrcType");
            if ("收款转入".equals(srcType)) continue;

            Double totalObj = numAt(n, "AROCMoney.TotalMoney");
            double totalMoney = totalObj == null ? 0.0 : totalObj;
            Double balObj = numAt(n, "AROCMoneyBalance.TotalMoney");
            double balMoney = balObj == null ? 0.0 : balObj;
            double paidMoney = Math.max(0.0, totalMoney - balMoney);

            String arDoc = strAt(n, "ARBillHead.DocNo");
            String proj = strAt(n, "Project.Code");
            String srcBill = strAt(n, "SrcBillNum");
            String itemCode = strAt(n, "Item.ItemCode");
            String itemName = strAt(n, "Item.ItemName");
            String custName = strAt(n, "ARBillHead.AccrueCust.Customer.Name");
            String suffix = normItemSuffix(itemCode);

            WorkingRow target = null;
            if (!proj.isEmpty() && byProj.containsKey(proj)) {
                target = pickBestItemRow(byProj.get(proj), suffix, itemName);
            }
            if (target == null && !srcBill.isEmpty()) {
                if (!suffix.isEmpty()) target = shipDocItemRow.get(srcBill + "#" + suffix);
                if (target == null) target = shipDocRow.get(srcBill);
                if (target == null && byProj.containsKey(srcBill)) {
                    target = pickBestItemRow(byProj.get(srcBill), suffix, itemName);
                }
            }
            if (target == null) {
                target = matchContractByCustomerAndItem(rows, custName, suffix, itemName,
                        monthSet.contains(ym) ? ym : BASELINE_MONTH);
            }
            if (target == null) continue;

            if (!arDoc.isEmpty() && Math.abs(totalMoney) > 1e-6) {
                arDocSlices.computeIfAbsent(arDoc, k -> new ArrayList<>())
                        .add(new ArLineSlice(target, totalMoney, paidMoney));
            }

            if (ym.compareTo(BASELINE_MONTH) < 0) {
                target.preOctArAccrued += totalMoney;
            } else if (monthSet.contains(ym)) {
                MonthBucket mb = target.bucket(ym);
                mb.arAccrued += totalMoney;
                String invNum = strAt(n, "InvoiceNum");
                if (!invNum.isBlank()) {
                    mb.invAmt += totalMoney;
                }
            }
        }

        // 手工期初应收单（如 AR-20260425002 -> 华新 2026年以前合同）加入 arDocSlices
        Set<String> galvNormCusts = new HashSet<>();
        Map<String, WorkingRow> pre2026ByCust = new HashMap<>();
        for (WorkingRow r : rows) {
            if (!r.normCust.isEmpty()) galvNormCusts.add(r.normCust);
            if ("pre2026".equals(r.rowKind) && !r.normCust.isEmpty()) {
                pre2026ByCust.put(r.normCust, r);
            }
        }
        for (ObjectNode n : rawByDs.getOrDefault("ds_u9c_arbill_misc", List.of())) {
            String arDoc = strAt(n, "ARBillHead.DocNo");
            String custName = strAt(n, "ARBillHead.AccrueCust.Customer.Name");
            String nc = normCust(custName);
            if (arDoc.isEmpty() || nc.isEmpty() || !matchesAnyCust(galvNormCusts, nc)) continue;
            Double totalObj = numAt(n, "AROCMoney.TotalMoney");
            double totalMoney = totalObj == null ? 0.0 : totalObj;
            Double balObj = numAt(n, "AROCMoneyBalance.TotalMoney");
            double paidMoney = Math.max(0.0, totalMoney - (balObj == null ? 0.0 : balObj));
            WorkingRow preRow = findByCustAlias(pre2026ByCust, nc);
            if (preRow == null) {
                preRow = matchOldestUnpaidContract(rows, nc, BASELINE_MONTH);
            }
            if (preRow != null && Math.abs(totalMoney) > 1e-6) {
                arDocSlices.computeIfAbsent(arDoc, k -> new ArrayList<>())
                        .add(new ArLineSlice(preRow, totalMoney, paidMoney));
            }
        }

        // 6. 回款金额 AA (ds_u9c_recbill_use + ds_u9c_arapply_l)
        Map<String, Set<String>> crToDrDocs = new HashMap<>();
        for (ObjectNode app : rawByDs.getOrDefault("ds_u9c_arapply_l", List.of())) {
            String cr = strAt(app, "CRDocNo");
            String dr = strAt(app, "DRDocNo");
            if (!cr.isEmpty() && !dr.isEmpty()) {
                crToDrDocs.computeIfAbsent(cr, k -> new LinkedHashSet<>()).add(dr);
            }
        }

        // 按收款日期升序处理，保证 FIFO 欠款冲抵顺序稳定
        List<ObjectNode> recUses = new ArrayList<>(rawByDs.getOrDefault("ds_u9c_recbill_use", List.of()));
        recUses.sort(Comparator
                .comparing((ObjectNode n) -> strAt(n, "RecBillHead.BusinessDate"))
                .thenComparing(n -> strAt(n, "RecBillHead.DocNo"))
                .thenComparingInt(n -> intAt(n, "LineNum")));

        for (ObjectNode u : recUses) {
            String ym = month7(strAt(u, "RecBillHead.BusinessDate"));
            if (!monthSet.contains(ym)) continue;
            Double moneyObj = numAt(u, "Money.OCMoney");
            double money = moneyObj == null ? 0.0 : moneyObj;
            if (Math.abs(money) < 1e-6) continue;

            String recDoc = strAt(u, "RecBillHead.DocNo");
            String prepayArDoc = strAt(u, "ARBillHead.DocNo");
            String useProj = strAt(u, "Project.Code");
            String custName = strAt(u, "RecBillHead.Cust.Customer.Name");
            String deptCode = strAt(u, "RecBillHead.Dept.Code");
            String nc = normCust(custName);

            Set<String> drDocs = new LinkedHashSet<>();
            if (!recDoc.isEmpty() && crToDrDocs.containsKey(recDoc)) drDocs.addAll(crToDrDocs.get(recDoc));
            if (!prepayArDoc.isEmpty() && crToDrDocs.containsKey(prepayArDoc)) drDocs.addAll(crToDrDocs.get(prepayArDoc));

            if (!drDocs.isEmpty()) {
                List<ArLineSlice> matchedSlices = new ArrayList<>();
                for (String dr : drDocs) {
                    List<ArLineSlice> s = arDocSlices.get(dr);
                    if (s != null) matchedSlices.addAll(s);
                }
                // 若核销指向其它事业部应收单（不在镀锌应收池），严禁计入镀锌事业部回款
                if (matchedSlices.isEmpty()) continue;

                double weightSum = matchedSlices.stream().mapToDouble(s -> s.paidMoney() > 0.01 ? s.paidMoney() : Math.abs(s.totalMoney())).sum();
                if (weightSum <= 1e-6) {
                    matchedSlices.get(0).row().bucket(ym).payAmt += money;
                } else {
                    for (ArLineSlice s : matchedSlices) {
                        double w = s.paidMoney() > 0.01 ? s.paidMoney() : Math.abs(s.totalMoney());
                        s.row().bucket(ym).payAmt += money * (w / weightSum);
                    }
                }
                continue;
            }

            // 未做核销单的收款/预收款：仅当属于智能镀锌事业部(0108)或明确属于镀锌客户时按欠款 FIFO 分摊
            boolean isGalvCust = !nc.isEmpty() && matchesAnyCust(galvNormCusts, nc);
            if (!"0108".equals(deptCode) && !isGalvCust) continue;

            if (!useProj.isEmpty() && byProj.containsKey(useProj)) {
                List<WorkingRow> cands = byProj.get(useProj);
                cands.get(0).bucket(ym).payAmt += money;
                continue;
            }
            allocateReceiptFifoByCustomer(rows, nc, ym, money, months);
        }
    }

    private void allocateReceiptFifoByCustomer(List<WorkingRow> rows,
                                               String normCustomer,
                                               String ym,
                                               double money,
                                               List<String> months) {
        List<WorkingRow> custRows = new ArrayList<>();
        for (WorkingRow r : rows) {
            if (custMatches(r.normCust, normCustomer) && !"uncontracted".equals(r.rowKind)) {
                custRows.add(r);
            }
        }
        if (custRows.isEmpty()) {
            for (WorkingRow r : rows) {
                if (custMatches(r.normCust, normCustomer)) custRows.add(r);
            }
        }
        if (custRows.isEmpty()) return;

        double remaining = money;
        if (remaining > 0) {
            // 第一轮：冲抵截至当月已开票未回款余额 (AJ - AK > 0)
            for (WorkingRow r : custRows) {
                if (remaining <= 0.01) break;
                double cumInv = r.openInvoice;
                double cumPay = r.openPayment;
                for (String m : months) {
                    if (m.compareTo(ym) > 0) break;
                    MonthBucket b = r.monthly.get(m);
                    if (b != null) {
                        cumInv += b.invAmt;
                        cumPay += b.payAmt;
                    }
                }
                double unpaid = cumInv - cumPay;
                if (unpaid > 0.01) {
                    double take = Math.min(remaining, unpaid);
                    r.bucket(ym).payAmt += take;
                    remaining -= take;
                }
            }
            // 第二轮：冲抵已发货未回款余额
            if (remaining > 0.01) {
                for (WorkingRow r : custRows) {
                    if (remaining <= 0.01) break;
                    if (r.unitPrice == null || r.unitPrice <= 0) continue;
                    double cumShip = r.openWhiteShipCum;
                    double cumPay = r.openPayment;
                    for (String m : months) {
                        if (m.compareTo(ym) > 0) break;
                        MonthBucket b = r.monthly.get(m);
                        if (b != null) {
                            cumShip += b.whiteShip;
                            cumPay += b.payAmt;
                        }
                    }
                    double shipDebt = cumShip * r.unitPrice - cumPay;
                    if (shipDebt > 0.01) {
                        double take = Math.min(remaining, shipDebt);
                        r.bucket(ym).payAmt += take;
                        remaining -= take;
                    }
                }
            }
            // 仍有剩余（纯预收款）：挂到该客户最新签订的合同行
            if (remaining > 0.01) {
                custRows.get(custRows.size() - 1).bucket(ym).payAmt += remaining;
            }
        } else {
            custRows.get(custRows.size() - 1).bucket(ym).payAmt += remaining;
        }
    }

    private WorkingRow matchOperationalRow(List<WorkingRow> rows,
                                           Map<String, List<WorkingRow>> byProj,
                                           String proj,
                                           String itemCode,
                                           String itemName,
                                           String custCat,
                                           String ym,
                                           java.util.function.Function<String, WorkingRow> uncontractedFactory) {
        String suffix = normItemSuffix(itemCode);
        if (!proj.isEmpty() && byProj.containsKey(proj)) {
            WorkingRow hit = pickBestItemRow(byProj.get(proj), suffix, itemName);
            if (hit != null) return hit;
        }
        WorkingRow byCustItem = matchContractByCustomerAndItem(rows, custCat, suffix, itemName, ym);
        if (byCustItem != null) return byCustItem;
        return uncontractedFactory.apply(custCat);
    }

    private void distributeBlackIncoming(List<WorkingRow> rows,
                                         String ym,
                                         double qty,
                                         String itemCode,
                                         String itemName,
                                         String custCat,
                                         java.util.function.Function<String, WorkingRow> uncontractedFactory) {
        String nc = normCust(custCat);
        String suffix = normItemSuffix(itemCode);

        List<WorkingRow> cands = new ArrayList<>();
        if (!nc.isEmpty() && !suffix.isEmpty()) {
            for (WorkingRow r : rows) {
                if ("contract".equals(r.rowKind) && custMatches(r.normCust, nc) && suffix.equals(r.itemSuffix)) {
                    cands.add(r);
                }
            }
        }
        if (cands.isEmpty() && !nc.isEmpty()) {
            for (WorkingRow r : rows) {
                if ("contract".equals(r.rowKind) && custMatches(r.normCust, nc) && productMatches(r.productName, itemName)) {
                    cands.add(r);
                }
            }
        }
        if (cands.isEmpty() && !nc.isEmpty()) {
            List<WorkingRow> allCustContracts = new ArrayList<>();
            for (WorkingRow r : rows) {
                if ("contract".equals(r.rowKind) && custMatches(r.normCust, nc)) {
                    allCustContracts.add(r);
                }
            }
            if (allCustContracts.size() == 1) {
                cands.add(allCustContracts.get(0));
            } else if (allCustContracts.size() > 1) {
                // 优先取当月有领料/入库/发货活动的合同
                for (WorkingRow r : allCustContracts) {
                    MonthBucket b = r.monthly.get(ym);
                    if (b != null && (b.blackOut > 0 || b.whiteIn > 0 || b.whiteShip > 0)) {
                        cands.add(r);
                    }
                }
                if (cands.isEmpty()) cands.add(allCustContracts.get(allCustContracts.size() - 1));
            }
        }

        if (cands.isEmpty()) {
            WorkingRow un = uncontractedFactory.apply(custCat);
            if (un != null) un.bucket(ym).blackIn += qty;
            return;
        }
        if (cands.size() == 1) {
            cands.get(0).bucket(ym).blackIn += qty;
            return;
        }

        // 多合同同客户同料号：按当月实际生产领料 U 比例分摊，其次按 W+X 比例，否则归入最新合同
        double sumU = 0.0;
        for (WorkingRow c : cands) {
            MonthBucket b = c.monthly.get(ym);
            if (b != null && b.blackOut > 0) sumU += b.blackOut;
        }
        if (sumU > 1e-6) {
            for (WorkingRow c : cands) {
                MonthBucket b = c.monthly.get(ym);
                double u = b == null ? 0.0 : Math.max(0.0, b.blackOut);
                if (u > 0) c.bucket(ym).blackIn += qty * (u / sumU);
            }
            return;
        }
        double sumWX = 0.0;
        for (WorkingRow c : cands) {
            MonthBucket b = c.monthly.get(ym);
            if (b != null) sumWX += Math.max(0.0, b.whiteIn) + Math.max(0.0, b.whiteShip);
        }
        if (sumWX > 1e-6) {
            for (WorkingRow c : cands) {
                MonthBucket b = c.monthly.get(ym);
                double wx = b == null ? 0.0 : Math.max(0.0, b.whiteIn) + Math.max(0.0, b.whiteShip);
                if (wx > 0) c.bucket(ym).blackIn += qty * (wx / sumWX);
            }
            return;
        }
        cands.get(cands.size() - 1).bucket(ym).blackIn += qty;
    }

    private WorkingRow matchContractByCustomerAndItem(List<WorkingRow> rows,
                                                      String custRaw,
                                                      String itemSuffix,
                                                      String itemName,
                                                      String ym) {
        String nc = normCust(custRaw);
        if (nc.isEmpty()) return null;
        List<WorkingRow> exact = new ArrayList<>();
        for (WorkingRow r : rows) {
            if (!"contract".equals(r.rowKind) || !custMatches(r.normCust, nc)) continue;
            if (!itemSuffix.isEmpty() && itemSuffix.equals(r.itemSuffix)) {
                exact.add(r);
            }
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return exact.get(exact.size() - 1);

        List<WorkingRow> byProd = new ArrayList<>();
        for (WorkingRow r : rows) {
            if (!"contract".equals(r.rowKind) || !custMatches(r.normCust, nc)) continue;
            if (productMatches(r.productName, itemName)) byProd.add(r);
        }
        if (!byProd.isEmpty()) return byProd.get(byProd.size() - 1);

        List<WorkingRow> anyCust = new ArrayList<>();
        for (WorkingRow r : rows) {
            if ("contract".equals(r.rowKind) && custMatches(r.normCust, nc)) anyCust.add(r);
        }
        if (anyCust.size() == 1) return anyCust.get(0);
        return null;
    }

    private WorkingRow matchOldestUnpaidContract(List<WorkingRow> rows, String nc, String ym) {
        for (WorkingRow r : rows) {
            if (custMatches(r.normCust, nc) && (r.openInvoice - r.openPayment > 0.01)) {
                return r;
            }
        }
        for (WorkingRow r : rows) {
            if (custMatches(r.normCust, nc)) return r;
        }
        return null;
    }

    private WorkingRow pickBestItemRow(List<WorkingRow> cands, String suffix, String itemName) {
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        if (!suffix.isEmpty()) {
            for (WorkingRow r : cands) {
                if (suffix.equals(r.itemSuffix)) return r;
            }
        }
        for (WorkingRow r : cands) {
            if (productMatches(r.productName, itemName)) return r;
        }
        return cands.get(0);
    }

    // ---------- 投影与全部 42 列公式计算 ----------

    private Map<String, Object> projectRow(WorkingRow r, String targetMonth, List<String> months) {
        double prevBlackIn = 0.0, prevBlackOut = 0.0, prevWhiteIn = 0.0, prevWhiteShip = 0.0;
        double prevInv = 0.0, prevPay = 0.0, prevArAccrued = 0.0;

        for (String m : months) {
            if (m.compareTo(targetMonth) >= 0) break;
            MonthBucket b = r.monthly.get(m);
            if (b != null) {
                prevBlackIn += b.blackIn;
                prevBlackOut += b.blackOut;
                prevWhiteIn += b.whiteIn;
                prevWhiteShip += b.whiteShip;
                prevInv += b.invAmt;
                prevPay += b.payAmt;
                prevArAccrued += b.arAccrued;
            }
        }
        MonthBucket cur = r.monthly.getOrDefault(targetMonth, new MonthBucket());

        double openBlackInCum = r.openBlackInCum + prevBlackIn;
        double openBlackOutCum = r.openBlackOutCum + prevBlackOut;
        double openWhiteInCum = r.openWhiteInCum + prevWhiteIn;

        // K–S 期初数据
        double colK = round4(r.openBlackStock + prevBlackIn - prevBlackOut);
        double colL = round4(r.openWhiteStock + prevWhiteIn - prevWhiteShip);
        double colM = round4(r.openWhiteShipCum + prevWhiteShip);
        double colN = calcShipContractAmt(r, colM);
        double colO = round2(r.openInvoice + prevInv);
        double colP = round2(r.openPayment + prevPay);
        double colQ = (r.estAmount != null || Math.abs(colO) > 1e-6) ? round2(nvlNum(r.estAmount) - colO) : 0.0;
        double colR = round2(colO - colP);
        double colS = calcDebt(r.unitPrice, colL, colN, colP, r.rowKind);

        // T–AA 当月经营数据（100% 上游实际）
        double colT = round4(cur.blackIn);
        double colU = round4(cur.blackOut);
        double colV = round4(colT - colU);
        double colW = round4(cur.whiteIn);
        double colX = round4(cur.whiteShip);
        double colY = round4(colW - colX);
        double colZ = round2(cur.invAmt);
        double colAA = round2(cur.payAmt);

        // AB–AO 期末数据
        double colAB = round4(openBlackInCum + colT);
        double colAC = round4(openBlackOutCum + colU);
        double colAD = round4(colAB - colAC);
        double colAE = round4(openWhiteInCum + colW);
        double colAH = round4(colM + colX);
        double colAF = round4(colAE - colAH);
        double colAG = r.unitPrice != null ? round2(colAF * r.unitPrice) : 0.0;
        double colAI = calcShipContractAmt(r, colAH);
        double colAJ = round2(colO + colZ);
        double colAK = round2(colP + colAA);

        // AL 已发货未做应收单的金额 / AM 已发货未开票金额（按系统实际应收与发票数据）
        double baseArAccrued = Math.max(r.openInvoice, r.preOctArAccrued);
        double endArAccrued = baseArAccrued + prevArAccrued + cur.arAccrued;
        double colAL = (colAI > 0.01) ? round2(Math.max(0.0, colAI - endArAccrued)) : 0.0;
        double colAM = (colAI > 0.01) ? round2(Math.max(0.0, colAI - colAJ)) : 0.0;
        double colAN = round2(colAJ - colAK);
        double colAO = calcDebt(r.unitPrice, colAF, colAI, colAK, r.rowKind);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_row_kind", r.rowKind);
        out.put("merge_group", r.mergeGroup);
        out.put("seq_no", r.seqNo == null ? "" : String.valueOf(r.seqNo));
        out.put("contract_no", r.contractNo);
        out.put("customer_name", r.customerName);
        out.put("sign_date", r.signDate);
        out.put("salesman", r.salesman);
        out.put("product_name", r.productName);
        out.put("unit_price", r.unitPrice);
        out.put("est_tonnage", r.estTonnage);
        out.put("est_amount", r.estAmount);
        out.put("settle_amount", r.settleAmount);

        out.put("open_black_stock", colK);
        out.put("open_white_stock", colL);
        out.put("open_white_ship", colM);
        out.put("open_ship_contract_amt", colN);
        out.put("open_inv_amt", colO);
        out.put("open_pay_amt", colP);
        out.put("open_uninv_amt", colQ);
        out.put("open_inv_unpaid_amt", colR);
        out.put("open_total_debt", colS);

        out.put("cur_black_in", colT);
        out.put("cur_black_out", colU);
        out.put("cur_black_net", colV);
        out.put("cur_white_in", colW);
        out.put("cur_white_ship", colX);
        out.put("cur_white_net", colY);
        out.put("cur_inv_amt", colZ);
        out.put("cur_pay_amt", colAA);

        out.put("end_black_in_cum", colAB);
        out.put("end_black_out_cum", colAC);
        out.put("end_black_stock", colAD);
        out.put("end_white_in_cum", colAE);
        out.put("end_white_stock", colAF);
        out.put("end_white_stock_amt", colAG);
        out.put("end_white_ship_cum", colAH);
        out.put("end_ship_contract_amt", colAI);
        out.put("end_inv_cum", colAJ);
        out.put("end_pay_cum", colAK);
        out.put("end_ship_no_ar_amt", colAL);
        out.put("end_ship_uninv_amt", colAM);
        out.put("end_inv_unpaid_amt", colAN);
        out.put("end_total_debt", colAO);
        out.put("remark", r.remark);
        return out;
    }

    private static double calcShipContractAmt(WorkingRow r, double cumShip) {
        if (r.unitPrice != null) return round2(cumShip * r.unitPrice);
        if (!"uncontracted".equals(r.rowKind) && r.estAmount != null && cumShip > 0) return round2(r.estAmount);
        return 0.0;
    }

    private static double calcDebt(Double unitPrice, double whiteStock, double shipAmt, double payAmt, String rowKind) {
        if ("uncontracted".equals(rowKind) && unitPrice == null) return 0.0;
        double stockVal = unitPrice != null ? whiteStock * unitPrice : 0.0;
        return round2(stockVal + shipAmt - payAmt);
    }

    private Map<String, Object> buildSummaryRow(List<Map<String, Object>> rows) {
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("_row_kind", "total");
        sum.put("merge_group", "TOTAL");
        sum.put("seq_no", "合计");
        sum.put("contract_no", "");
        sum.put("customer_name", "");
        sum.put("sign_date", "");
        sum.put("salesman", "");
        sum.put("product_name", "");
        sum.put("unit_price", null);
        sum.put("remark", "");

        String[] tonKeys = {
                "est_tonnage",
                "open_black_stock", "open_white_stock", "open_white_ship",
                "cur_black_in", "cur_black_out", "cur_black_net", "cur_white_in", "cur_white_ship", "cur_white_net",
                "end_black_in_cum", "end_black_out_cum", "end_black_stock",
                "end_white_in_cum", "end_white_stock", "end_white_ship_cum"
        };
        String[] moneyKeys = {
                "est_amount", "settle_amount",
                "open_ship_contract_amt", "open_inv_amt", "open_pay_amt", "open_uninv_amt", "open_inv_unpaid_amt", "open_total_debt",
                "cur_inv_amt", "cur_pay_amt",
                "end_white_stock_amt", "end_ship_contract_amt", "end_inv_cum", "end_pay_cum",
                "end_ship_no_ar_amt", "end_ship_uninv_amt", "end_inv_unpaid_amt", "end_total_debt"
        };
        for (String k : tonKeys) {
            double acc = 0.0;
            for (Map<String, Object> r : rows) {
                Object v = r.get(k);
                if (v instanceof Number n) acc += n.doubleValue();
            }
            sum.put(k, round4(acc));
        }
        for (String k : moneyKeys) {
            double acc = 0.0;
            for (Map<String, Object> r : rows) {
                Object v = r.get(k);
                if (v instanceof Number n) acc += n.doubleValue();
            }
            sum.put(k, round2(acc));
        }
        return sum;
    }

    private List<Map<String, Object>> applyRowFilters(List<Map<String, Object>> rows, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return rows;
        String fContract = extractStringFilter(filters, "contract_no").toLowerCase();
        String fCust = extractStringFilter(filters, "customer_name").toLowerCase();
        String fSeller = extractStringFilter(filters, "salesman").toLowerCase();
        String fProd = extractStringFilter(filters, "product_name").toLowerCase();
        if (fContract.isEmpty() && fCust.isEmpty() && fSeller.isEmpty() && fProd.isEmpty()) return rows;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!fContract.isEmpty() && !String.valueOf(r.getOrDefault("contract_no", "")).toLowerCase().contains(fContract)) continue;
            if (!fCust.isEmpty() && !String.valueOf(r.getOrDefault("customer_name", "")).toLowerCase().contains(fCust)) continue;
            if (!fSeller.isEmpty() && !String.valueOf(r.getOrDefault("salesman", "")).toLowerCase().contains(fSeller)) continue;
            if (!fProd.isEmpty() && !String.valueOf(r.getOrDefault("product_name", "")).toLowerCase().contains(fProd)) continue;
            out.add(r);
        }
        return out;
    }

    // ---------- 数据加载（支持 local 与 realtime 双模式 + 空库自动回退实时） ----------

    private Map<String, List<ObjectNode>> loadDatasets(List<ReportQueryEngine.TraceCall> trace) {
        boolean preferLocal = queryModeService.isLocal();
        Map<String, List<ObjectNode>> out = new ConcurrentHashMap<>();

        List<String> toFetchRemote = new ArrayList<>();
        for (String dsCode : REQUIRED_DATASETS) {
            if (preferLocal) {
                long t0 = System.currentTimeMillis();
                List<ObjectNode> localRows = syncStore.loadRaw(dsCode);
                if (!localRows.isEmpty()) {
                    out.put(dsCode, localRows);
                    trace.add(new ReportQueryEngine.TraceCall("local", "sync_records", dsCode,
                            localRows.size(), System.currentTimeMillis() - t0));
                    continue;
                }
            }
            toFetchRemote.add(dsCode);
        }

        if (!toFetchRemote.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, toFetchRemote.size()));
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String dsCode : toFetchRemote) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        List<ObjectNode> fetched = fetchDatasetFromUpstream(dsCode, trace);
                        out.put(dsCode, fetched);
                    }, pool));
                }
                for (CompletableFuture<Void> f : futures) f.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new BizException("拉取 U9 数据失败: " + cause.getMessage());
            } finally {
                pool.shutdownNow();
            }
        }
        return out;
    }

    private List<ObjectNode> fetchDatasetFromUpstream(String dsCode, List<ReportQueryEngine.TraceCall> trace) {
        DatasetDef ds = datasetRepo.findById(dsCode)
                .orElseThrow(() -> new BizException("缺少数据集配置: " + dsCode));
        ApiEndpointDef ep = endpointRepo.findById(ds.getEndpointCode())
                .orElseThrow(() -> new BizException("缺少接口配置: " + ds.getEndpointCode()));
        DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode())
                .orElseThrow(() -> new BizException("缺少数据源配置: " + ep.getDataSourceCode()));

        SyncConfig sc = syncConfigRepo.findById(dsCode).orElse(null);
        int pageSize = sc != null && sc.getPageSize() != null ? sc.getPageSize() : 2000;
        int maxPages = sc != null && sc.getMaxPages() != null ? sc.getMaxPages() : 10;

        long t0 = System.currentTimeMillis();
        List<ObjectNode> all = new ArrayList<>();
        String lastUrl = "";
        for (int page = 1; page <= maxPages; page++) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("page", page);
            ctx.put("pageSize", pageSize);
            var res = upstream.fetchList(src, ep, ctx);
            lastUrl = res.url();
            List<ObjectNode> chunk = res.rawRows();
            if (chunk == null || chunk.isEmpty()) break;
            all.addAll(chunk);
            if (chunk.size() < pageSize) break;
        }
        trace.add(new ReportQueryEngine.TraceCall("realtime", ep.getCode(), lastUrl,
                all.size(), System.currentTimeMillis() - t0));
        return all;
    }

    // ---------- 双层分组表头 Excel (.xlsx) 导出 ----------

    public byte[] exportXlsx(Map<String, Object> filters) {
        ScrollReportView view = computeView(filters, 1, 10000);
        List<Map<String, Object>> visCols = view.columns().stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("hidden")))
                .toList();
        List<Map<String, Object>> dataRows = view.rows();
        Map<String, Object> totalRow = view.summaryRow();

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            String sheetTitle = "销售合同滚动表-" + view.month();
            Sheet sheet = wb.createSheet(sheetTitle);

            // 样式定义
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setFontName("微软雅黑");

            XSSFFont headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setFontHeightInPoints((short) 10);
            headFont.setFontName("微软雅黑");

            XSSFFont bodyFont = wb.createFont();
            bodyFont.setFontHeightInPoints((short) 10);
            bodyFont.setFontName("微软雅黑");

            DataFormat df = wb.createDataFormat();

            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            XSSFCellStyle headBase = createHeaderStyle(wb, headFont, new byte[]{(byte) 226, (byte) 239, (byte) 218});
            XSSFCellStyle headOpen = createHeaderStyle(wb, headFont, new byte[]{(byte) 221, (byte) 235, (byte) 247});
            XSSFCellStyle headCur  = createHeaderStyle(wb, headFont, new byte[]{(byte) 255, (byte) 242, (byte) 204});
            XSSFCellStyle headEnd  = createHeaderStyle(wb, headFont, new byte[]{(byte) 226, (byte) 239, (byte) 218});

            CellStyle textStyle = borderedStyle(wb, bodyFont, HorizontalAlignment.LEFT);
            CellStyle centerStyle = borderedStyle(wb, bodyFont, HorizontalAlignment.CENTER);
            CellStyle tonStyle = borderedStyle(wb, bodyFont, HorizontalAlignment.RIGHT);
            tonStyle.setDataFormat(df.getFormat("#,##0.####"));
            CellStyle moneyStyle = borderedStyle(wb, bodyFont, HorizontalAlignment.RIGHT);
            moneyStyle.setDataFormat(df.getFormat("#,##0.00"));

            XSSFCellStyle totalTextStyle = (XSSFCellStyle) borderedStyle(wb, headFont, HorizontalAlignment.CENTER);
            totalTextStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 242, (byte) 242, (byte) 242}, null));
            totalTextStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle totalTonStyle = (XSSFCellStyle) borderedStyle(wb, headFont, HorizontalAlignment.RIGHT);
            totalTonStyle.setDataFormat(df.getFormat("#,##0.####"));
            totalTonStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 242, (byte) 242, (byte) 242}, null));
            totalTonStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle totalMoneyStyle = (XSSFCellStyle) borderedStyle(wb, headFont, HorizontalAlignment.RIGHT);
            totalMoneyStyle.setDataFormat(df.getFormat("#,##0.00"));
            totalMoneyStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 242, (byte) 242, (byte) 242}, null));
            totalMoneyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int nCols = visCols.size();

            // Row 0: 报表大标题
            Row r0 = sheet.createRow(0);
            r0.setHeightInPoints(28);
            for (int c = 0; c < nCols; c++) {
                Cell cell = r0.createCell(c);
                cell.setCellStyle(titleStyle);
                if (c == 0) cell.setCellValue("北方安防公司智能镀锌事业部销售合同滚动统计报表（" + view.monthLabel() + "）");
            }
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, nCols - 1));

            // Row 1 & Row 2: 双层分组表头
            Row r1 = sheet.createRow(1);
            Row r2 = sheet.createRow(2);
            r1.setHeightInPoints(22);
            r2.setHeightInPoints(28);

            int c = 0;
            while (c < nCols) {
                Map<String, Object> col = visCols.get(c);
                String grp = String.valueOf(col.getOrDefault("group", ""));
                String sec = String.valueOf(col.getOrDefault("section", "base"));
                CellStyle hs = switch (sec) {
                    case "open" -> headOpen;
                    case "cur" -> headCur;
                    case "end" -> headEnd;
                    default -> headBase;
                };
                if (grp.isBlank()) {
                    Cell top = r1.createCell(c);
                    top.setCellValue(String.valueOf(col.get("label")));
                    top.setCellStyle(hs);
                    Cell bot = r2.createCell(c);
                    bot.setCellStyle(hs);
                    sheet.addMergedRegion(new CellRangeAddress(1, 2, c, c));
                    c++;
                } else {
                    int end = c + 1;
                    while (end < nCols && grp.equals(String.valueOf(visCols.get(end).getOrDefault("group", "")))) {
                        end++;
                    }
                    for (int k = c; k < end; k++) {
                        Cell top = r1.createCell(k);
                        top.setCellStyle(hs);
                        if (k == c) top.setCellValue(grp);
                        Cell bot = r2.createCell(k);
                        bot.setCellValue(String.valueOf(visCols.get(k).get("label")));
                        bot.setCellStyle(hs);
                    }
                    if (end - c > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(1, 1, c, end - 1));
                    }
                    c = end;
                }
            }

            // 计算纵向合并跨度（包含隐藏 merge_group 列分层，确保不同合同绝不误合并）
            int[][] fullSpans = computeMergeSpans(view.columns(), dataRows);
            List<Integer> visIndices = new ArrayList<>();
            for (int idx = 0; idx < view.columns().size(); idx++) {
                if (!Boolean.TRUE.equals(view.columns().get(idx).get("hidden"))) visIndices.add(idx);
            }

            // 写入明细行
            int baseExcelRow = 3;
            for (int i = 0; i < dataRows.size(); i++) {
                Row er = sheet.createRow(baseExcelRow + i);
                er.setHeightInPoints(20);
                Map<String, Object> rowData = dataRows.get(i);
                for (int vc = 0; vc < visIndices.size(); vc++) {
                    int origCol = visIndices.get(vc);
                    Map<String, Object> colDef = view.columns().get(origCol);
                    String key = String.valueOf(colDef.get("key"));
                    String kind = String.valueOf(colDef.getOrDefault("kind", "text"));
                    boolean isMerge = Boolean.TRUE.equals(colDef.get("merge"));
                    int span = fullSpans[i][origCol];

                    Cell cell = er.createCell(vc);
                    CellStyle st = switch (kind) {
                        case "ton" -> tonStyle;
                        case "money" -> moneyStyle;
                        case "center" -> centerStyle;
                        default -> isMerge ? centerStyle : textStyle;
                    };
                    cell.setCellStyle(st);

                    if (span == 0) continue;
                    Object val = rowData.get(key);
                    writeCellValue(cell, val);
                    if (span > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(baseExcelRow + i, baseExcelRow + i + span - 1, vc, vc));
                    }
                }
            }

            // 写入底部合计行
            if (totalRow != null) {
                Row tr = sheet.createRow(baseExcelRow + dataRows.size());
                tr.setHeightInPoints(22);
                for (int vc = 0; vc < visIndices.size(); vc++) {
                    Map<String, Object> colDef = view.columns().get(visIndices.get(vc));
                    String key = String.valueOf(colDef.get("key"));
                    String kind = String.valueOf(colDef.getOrDefault("kind", "text"));
                    Cell cell = tr.createCell(vc);
                    cell.setCellStyle(switch (kind) {
                        case "ton" -> totalTonStyle;
                        case "money" -> totalMoneyStyle;
                        default -> totalTextStyle;
                    });
                    writeCellValue(cell, totalRow.get(key));
                }
            }

            // 列宽与冻结窗格
            for (int vc = 0; vc < visCols.size(); vc++) {
                int w = Number.class.isAssignableFrom(Integer.class) && visCols.get(vc).get("width") instanceof Number n
                        ? n.intValue() : 14;
                sheet.setColumnWidth(vc, w * 256);
            }
            sheet.createFreezePane(3, 3);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("导出合同滚动表 Excel 失败: " + e.getMessage());
        }
    }

    private static XSSFCellStyle createHeaderStyle(XSSFWorkbook wb, Font font, byte[] rgb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        s.setFillForegroundColor(new XSSFColor(rgb, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private static CellStyle borderedStyle(Workbook wb, Font font, HorizontalAlignment align) {
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setAlignment(align);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private static void writeCellValue(Cell cell, Object val) {
        if (val == null) {
            cell.setBlank();
        } else if (val instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            String s = String.valueOf(val);
            if (s.isEmpty()) cell.setBlank();
            else cell.setCellValue(s);
        }
    }

    private static int[][] computeMergeSpans(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {
        int n = rows.size();
        int[][] span = new int[n][columns.size()];
        for (int[] r : span) Arrays.fill(r, 1);
        int[] parentEnd = new int[n];
        Arrays.fill(parentEnd, n);
        for (int c = 0; c < columns.size(); c++) {
            if (!Boolean.TRUE.equals(columns.get(c).get("merge"))) continue;
            String key = String.valueOf(columns.get(c).get("key"));
            int[] end = new int[n];
            int i = 0;
            while (i < n) {
                String cur = String.valueOf(rows.get(i).getOrDefault(key, ""));
                int j = i + 1;
                while (j < parentEnd[i] && cur.equals(String.valueOf(rows.get(j).getOrDefault(key, "")))) j++;
                span[i][c] = j - i;
                for (int k = i + 1; k < j; k++) span[k][c] = 0;
                for (int k = i; k < j; k++) end[k] = j;
                i = j;
            }
            parentEnd = end;
        }
        return span;
    }

    // ---------- 列定义与分组表头元数据 ----------

    public static List<Map<String, Object>> buildGroupHeaders(String month) {
        String ml = formatMonthLabel(month);
        return List.of(
                Map.of("section", "base", "label", "合同档案", "colSpan", 10),
                Map.of("section", "open", "label", "期初数据", "colSpan", 9),
                Map.of("section", "cur",  "label", ml + "经营数据", "colSpan", 8),
                Map.of("section", "end",  "label", "截至" + ml + "期末数据", "colSpan", 14),
                Map.of("section", "tail", "label", "备注", "colSpan", 1)
        );
    }

    public static List<Map<String, Object>> buildColumns(String month) {
        String ml = formatMonthLabel(month);
        String gOpen = "期初数据";
        String gCur = ml + "经营数据";
        String gEnd = "截至" + ml + "期末数据";

        List<Map<String, Object>> cols = new ArrayList<>();
        // 隐藏的合同分组边界列，保证无合同散户或不同合同的空白字段不会跨合同合并
        cols.add(col("merge_group", "分组键", "", "base", "text", true, true, 0));

        // A–J 合同档案（10列）
        cols.add(col("seq_no",          "序号",           "", "base", "center", false, true,  7));
        cols.add(col("contract_no",     "合同号",         "", "base", "text",   false, true,  18));
        cols.add(col("customer_name",   "客户名称",       "", "base", "text",   false, true,  26));
        cols.add(col("sign_date",       "签订日期",       "", "base", "center", false, true,  12));
        cols.add(col("salesman",        "签订人",         "", "base", "center", false, true,  10));
        cols.add(col("product_name",    "产品",           "", "base", "text",   false, false, 18));
        cols.add(col("unit_price",      "单价",           "", "base", "money",  false, false, 11));
        cols.add(col("est_tonnage",     "预计总吨位/开口", "", "base", "ton",    false, false, 14));
        cols.add(col("est_amount",      "预计合同金额",   "", "base", "money",  false, false, 15));
        cols.add(col("settle_amount",   "结算金额",       "", "base", "money",  false, false, 14));

        // K–S 期初数据（9列）
        cols.add(col("open_black_stock",       "黑件库存",         gOpen, "open", "ton",   false, false, 12));
        cols.add(col("open_white_stock",       "白件库存",         gOpen, "open", "ton",   false, false, 12));
        cols.add(col("open_white_ship",        "白件累计发货",     gOpen, "open", "ton",   false, false, 13));
        cols.add(col("open_ship_contract_amt", "累计发货计算合同额", gOpen, "open", "money", false, false, 16));
        cols.add(col("open_inv_amt",           "开票金额",         gOpen, "open", "money", false, false, 15));
        cols.add(col("open_pay_amt",           "回款金额",         gOpen, "open", "money", false, false, 15));
        cols.add(col("open_uninv_amt",         "剩余未开票金额",   gOpen, "open", "money", false, false, 15));
        cols.add(col("open_inv_unpaid_amt",    "开票未回款金额",   gOpen, "open", "money", false, false, 15));
        cols.add(col("open_total_debt",        "计算总欠款金额",   gOpen, "open", "money", false, false, 15));

        // T–AA 当月经营数据（8列）
        cols.add(col("cur_black_in",   "黑件来料",     gCur, "cur", "ton",   false, false, 12));
        cols.add(col("cur_black_out",  "黑件出库",     gCur, "cur", "ton",   false, false, 12));
        cols.add(col("cur_black_net",  "黑件线上暂存", gCur, "cur", "ton",   false, false, 13));
        cols.add(col("cur_white_in",   "白件入库",     gCur, "cur", "ton",   false, false, 12));
        cols.add(col("cur_white_ship", "白件发货",     gCur, "cur", "ton",   false, false, 12));
        cols.add(col("cur_white_net",  "白件线上暂存", gCur, "cur", "ton",   false, false, 13));
        cols.add(col("cur_inv_amt",    "开票金额",     gCur, "cur", "money", false, false, 15));
        cols.add(col("cur_pay_amt",    "回款金额",     gCur, "cur", "money", false, false, 15));

        // AB–AO 期末数据（14列）
        cols.add(col("end_black_in_cum",      "黑件累计来料",         gEnd, "end", "ton",   false, false, 13));
        cols.add(col("end_black_out_cum",     "黑件累计出库",         gEnd, "end", "ton",   false, false, 13));
        cols.add(col("end_black_stock",       "黑件库存",             gEnd, "end", "ton",   false, false, 12));
        cols.add(col("end_white_in_cum",      "白件累计入库",         gEnd, "end", "ton",   false, false, 13));
        cols.add(col("end_white_stock",       "白件累计库存",         gEnd, "end", "ton",   false, false, 13));
        cols.add(col("end_white_stock_amt",   "白件库存计算合同额",   gEnd, "end", "money", false, false, 16));
        cols.add(col("end_white_ship_cum",    "白件累计发货",         gEnd, "end", "ton",   false, false, 13));
        cols.add(col("end_ship_contract_amt", "白件累计发货计算合同额", gEnd, "end", "money", false, false, 18));
        cols.add(col("end_inv_cum",           "累计开票金额",         gEnd, "end", "money", false, false, 15));
        cols.add(col("end_pay_cum",           "累计回款金额",         gEnd, "end", "money", false, false, 15));
        cols.add(col("end_ship_no_ar_amt",    "已发货未做应收单的金额", gEnd, "end", "money", false, false, 18));
        cols.add(col("end_ship_uninv_amt",    "已发货未开票金额",     gEnd, "end", "money", false, false, 15));
        cols.add(col("end_inv_unpaid_amt",    "开票未回款金额",       gEnd, "end", "money", false, false, 15));
        cols.add(col("end_total_debt",        "总欠款金额",           gEnd, "end", "money", false, false, 15));

        // AP 备注
        cols.add(col("remark", "备注", "", "tail", "text", false, false, 22));
        return cols;
    }

    private static Map<String, Object> col(String key, String label, String group, String section,
                                           String kind, boolean hidden, boolean merge, int width) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("from", key);
        m.put("group", group);
        m.put("section", section);
        m.put("kind", kind);
        if (hidden) m.put("hidden", true);
        if (merge) m.put("merge", true);
        m.put("width", width);
        return m;
    }

    // ---------- 字符串/日期/数值辅助方法 ----------

    public static String extractTargetMonth(Map<String, Object> filters) {
        if (filters != null) {
            Object mv = filters.get("month");
            if (mv instanceof Map<?, ?> m && m.get("start") != null) {
                String s = String.valueOf(m.get("start")).trim();
                if (s.length() >= 7 && s.substring(0, 7).matches("\\d{4}-\\d{2}")) return s.substring(0, 7);
            } else if (mv != null) {
                String s = String.valueOf(mv).trim();
                if (s.length() >= 7 && s.substring(0, 7).matches("\\d{4}-\\d{2}")) return s.substring(0, 7);
            }
        }
        String nowYm = YearMonth.now().toString();
        return nowYm.compareTo(BASELINE_MONTH) < 0 ? BASELINE_MONTH : nowYm;
    }

    public static String formatMonthLabel(String yyyyMm) {
        if (yyyyMm != null && yyyyMm.matches("\\d{4}-\\d{2}")) {
            int y = Integer.parseInt(yyyyMm.substring(0, 4));
            int m = Integer.parseInt(yyyyMm.substring(5, 7));
            return y + "年" + m + "月";
        }
        return String.valueOf(yyyyMm);
    }

    private static List<String> enumerateMonths(String startYm, String endYm) {
        List<String> out = new ArrayList<>();
        YearMonth cur = YearMonth.parse(startYm);
        YearMonth end = YearMonth.parse(endYm);
        while (!cur.isAfter(end)) {
            out.add(cur.toString());
            cur = cur.plusMonths(1);
        }
        return out;
    }

    /** 统一黑白件编码后缀：1104057-0002 与 1205057-0002 均归一为 057-0002 */
    private static String normItemSuffix(String code) {
        if (code == null) return "";
        String s = code.trim();
        if (s.length() > 4 && (s.startsWith("1104") || s.startsWith("1205"))) {
            return s.substring(4);
        }
        return s;
    }

    private static String cleanProductName(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > 2 && (t.endsWith("白件") || t.endsWith("黑件"))) {
            t = t.substring(0, t.length() - 2).trim();
        }
        return t;
    }

    private static String normCust(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        String[] strips = {
                "有限责任公司", "股份有限公司", "（有限合伙）", "(有限合伙)", "（集团）", "(集团)", "集团",
                "有限公司", "有限公", "总厂",
                "内蒙古", "包头市", "包头", "大同市", "大同", "山西", "陕西", "江苏", "山东",
                "上海", "北京", "河北", "甘肃", "贵州", "盐城", "芜湖", "乌海市", "乌海"
        };
        for (String p : strips) s = s.replace(p, "");
        return s.replaceAll("\\s+", "");
    }

    private static boolean custMatches(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        // 区分 云涛机械 vs 云涛电气
        if ((a.contains("云涛") || b.contains("云涛")) && (a.contains("电气") != b.contains("电气"))) {
            return false;
        }
        if (a.length() >= 2 && b.length() >= 2 && (a.contains(b) || b.contains(a))) return true;
        return false;
    }

    private static boolean matchesAnyCust(Set<String> set, String nc) {
        if (set.contains(nc)) return true;
        for (String s : set) {
            if (custMatches(s, nc)) return true;
        }
        return false;
    }

    private static WorkingRow findByCustAlias(Map<String, WorkingRow> map, String nc) {
        WorkingRow direct = map.get(nc);
        if (direct != null) return direct;
        for (Map.Entry<String, WorkingRow> e : map.entrySet()) {
            if (custMatches(e.getKey(), nc)) return e.getValue();
        }
        return null;
    }

    private static boolean productMatches(String rowProd, String u9ItemName) {
        if (rowProd == null || u9ItemName == null) return false;
        String a = cleanProductName(rowProd).replaceAll("[\\s（）()、]", "");
        String b = cleanProductName(u9ItemName).replaceAll("[\\s（）()、]", "");
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        return a.length() >= 2 && b.length() >= 2 && a.substring(0, 2).equals(b.substring(0, 2));
    }

    private static String extractStringFilter(Map<String, Object> filters, String key) {
        Object v = filters.get(key);
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) {
            Object val = m.get("value");
            return val == null ? "" : String.valueOf(val).trim();
        }
        return String.valueOf(v).trim();
    }

    private static String strAt(JsonNode n, String path) {
        Object v = JsonPaths.read(n, path);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int intAt(JsonNode n, String path) {
        Double d = numAt(n, path);
        return d == null ? 0 : d.intValue();
    }

    private static Double numAt(JsonNode n, String path) {
        Object v = JsonPaths.read(n, path);
        if (v == null) return null;
        if (v instanceof Number num) return num.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String month7(String s) {
        return s != null && s.length() >= 7 ? s.substring(0, 7) : "";
    }

    private static String date10(String s) {
        return s != null && s.length() >= 10 ? s.substring(0, 10) : (s == null ? "" : s);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    private static void addNonBlank(Set<String> set, String v) {
        if (v != null && !v.isBlank()) set.add(v.trim());
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static double nvlNum(Double d) {
        return d == null ? 0.0 : d;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
