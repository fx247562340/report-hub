package com.reporthub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.domain.ReportDef;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.repo.ReportRepo;
import com.reporthub.service.ContractScrollService;
import com.reporthub.service.DashboardService;
import com.reporthub.service.SyncService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * AI Agent 只读业务工具注册与执行器。
 * 所有聚合、合计、排序、环比均在 Java 层确定性算完，大模型只做意图调度与自然语言解读。
 */
@Component
public class AiToolExecutor {

    public record DrillLink(String label, String to) {}

    public record ToolResult(
            String toolName,
            String displayLabel,
            String summaryText,
            Map<String, Object> payload,
            List<DrillLink> links
    ) {}

    private final DashboardService dashboardService;
    private final ContractScrollService contractScrollService;
    private final ReportQueryEngine queryEngine;
    private final ReportRepo reportRepo;
    private final SyncService syncService;
    private final ObjectMapper mapper;

    public AiToolExecutor(DashboardService dashboardService,
                          ContractScrollService contractScrollService,
                          ReportQueryEngine queryEngine,
                          ReportRepo reportRepo,
                          SyncService syncService,
                          ObjectMapper mapper) {
        this.dashboardService = dashboardService;
        this.contractScrollService = contractScrollService;
        this.queryEngine = queryEngine;
        this.reportRepo = reportRepo;
        this.syncService = syncService;
        this.mapper = mapper;
    }

    public String displayLabelOf(String toolName) {
        return switch (toolName) {
            case "get_executive_overview" -> "经营看板总览";
            case "query_contract_scroll" -> "销售合同滚动统计";
            case "compare_contract_months" -> "跨月经营对比";
            case "query_hang_report" -> "挂次生产与重量差异";
            case "query_cust_monthly_flow" -> "客户月度黑白件出入库";
            case "get_sync_health" -> "数据同步状态巡检";
            default -> toolName;
        };
    }

    /** 标准 OpenAI Function Calling 工具清单 */
    public List<Map<String, Object>> toolDefinitions(boolean includeAdminTools) {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(fn(
                "get_executive_overview",
                "获取指定月份的经营驾驶舱总览：包含当月白件发货(吨)、开票金额(元)、回款金额(元)、期末总欠款(元)、期末在制库存(吨)及其环比变化、近6个月趋势、客户欠款Top8与负库存预警数。",
                Map.of("month", prop("string", "统计月份，格式 YYYY-MM，例如 2026-11；不填则默认当前最新月份")),
                List.of()
        ));

        tools.add(fn(
                "query_contract_scroll",
                "查询《销售合同滚动统计报表》明细与汇总：支持按客户简称(自动模糊匹配)、合同号、仅看欠款、仅看黑白件负库存筛选，并由后端计算合计值与Top排序。",
                Map.of(
                        "month", prop("string", "统计月份，格式 YYYY-MM，默认当前最新月份"),
                        "customerKeyword", prop("string", "客户名称或简称关键词（支持模糊匹配，如“华泰”“顺达”）"),
                        "contractNo", prop("string", "纸质合同号或项目号关键词"),
                        "onlyNegativeStock", prop("boolean", "是否仅筛选出现黑件或白件负库存的异常合同"),
                        "onlyDebt", prop("boolean", "是否仅筛选期末总欠款大于0的合同"),
                        "sortBy", propEnum("排序指标", List.of("end_total_debt", "cur_white_ship", "cur_inv_amt", "cur_pay_amt", "end_black_stock", "end_white_stock")),
                        "limit", prop("integer", "返回明细条数上限，默认 12，最大 20")
                ),
                List.of()
        ));

        tools.add(fn(
                "compare_contract_months",
                "对比两个月份的经营表现（总发货、开票、回款、期末欠款、在制库存）并自动找出客户欠款或发货变动最大的 Top 列表。",
                Map.of(
                        "monthA", prop("string", "基准月份(较早月)，格式 YYYY-MM，例如 2026-10"),
                        "monthB", prop("string", "对比月份(较新月)，格式 YYYY-MM，例如 2026-11"),
                        "customerKeyword", prop("string", "可选，限定单个客户简称进行跨月对比"),
                        "metric", propEnum("重点排行维度", List.of("debt_change", "ship_change", "pay_change")),
                        "limit", prop("integer", "返回变动排行条数，默认 8，最大 15")
                ),
                List.of("monthA", "monthB")
        ));

        tools.add(fn(
                "query_hang_report",
                "查询生产挂次报表：reportType=daily 查《挂次日报表》（铁损/锌耗/锌层厚度异常与产线产量），reportType=weight_diff 查《挂次白件重量差异表》（MES称重与U9完工/入库重量超差对比）。",
                Map.of(
                        "reportType", propEnum("报表类型：daily=挂次日报表，weight_diff=白件重量差异表", List.of("daily", "weight_diff")),
                        "month", prop("string", "按月查询，格式 YYYY-MM（与 startDate/endDate 二选一）"),
                        "startDate", prop("string", "开始日期 YYYY-MM-DD"),
                        "endDate", prop("string", "结束日期 YYYY-MM-DD"),
                        "customerKeyword", prop("string", "可选，客户简称关键词"),
                        "onlyAbnormal", prop("boolean", "是否仅看异常记录（weight_diff 时为重量不一致；daily 时为铁损或锌耗异常）"),
                        "limit", prop("integer", "返回明细条数上限，默认 12，最大 20")
                ),
                List.of("reportType")
        ));

        tools.add(fn(
                "query_cust_monthly_flow",
                "查询客户月度出入库汇总：direction=in 查《客户月度黑件入库表》(来料吨位)，direction=out 查《客户月度白件出库表》(成品发货吨位)，自动按客户汇总排行。",
                Map.of(
                        "direction", propEnum("方向：in=黑件入库，out=白件出库", List.of("in", "out")),
                        "month", prop("string", "统计月份 YYYY-MM，默认当前最新月"),
                        "customerKeyword", prop("string", "可选，客户简称关键词"),
                        "limit", prop("integer", "返回客户排行条数，默认 12，最大 20")
                ),
                List.of("direction")
        ));

        if (includeAdminTools) {
            tools.add(fn(
                    "get_sync_health",
                    "巡检 13 张本地同步表的运行状态：查看启用数、本地行数、最近同步时间以及是否存在同步失败的异常表（仅管理员可用）。",
                    Map.of(),
                    List.of()
            ));
        }
        return tools;
    }

    public ToolResult execute(String toolName, String rawArguments, boolean isAdmin) {
        JsonNode args;
        try {
            args = (rawArguments == null || rawArguments.isBlank())
                    ? mapper.createObjectNode()
                    : mapper.readTree(rawArguments);
        } catch (Exception e) {
            args = mapper.createObjectNode();
        }
        return switch (toolName) {
            case "get_executive_overview" -> runExecutiveOverview(args);
            case "query_contract_scroll" -> runContractScroll(args);
            case "compare_contract_months" -> runCompareMonths(args);
            case "query_hang_report" -> runHangReport(args);
            case "query_cust_monthly_flow" -> runCustFlow(args);
            case "get_sync_health" -> {
                if (!isAdmin) {
                    yield new ToolResult(toolName, displayLabelOf(toolName), "无权限访问同步巡检",
                            Map.of("error", "当前账号无权查看运维同步状态"), List.of());
                }
                yield runSyncHealth();
            }
            default -> new ToolResult(toolName, toolName, "未知工具",
                    Map.of("error", "不支持的工具: " + toolName), List.of());
        };
    }

    @SuppressWarnings("unchecked")
    private ToolResult runExecutiveOverview(JsonNode args) {
        String month = optText(args, "month", "");
        Map<String, Object> ov = dashboardService.getOverview(month.isBlank() ? null : month, false);
        Map<String, Object> exec = (Map<String, Object>) ov.getOrDefault("executive", Map.of());
        String actualMonth = String.valueOf(exec.getOrDefault("month", latestMonth()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("month", actualMonth);
        payload.put("prevMonth", exec.get("prevMonth"));
        payload.put("contractCount", exec.get("contractCount"));
        payload.put("kpis", exec.get("kpis"));
        payload.put("monthlySeries", exec.get("monthlySeries"));
        payload.put("topDebtCustomers", exec.get("topDebtCustomers"));
        payload.put("stockAlertTotal", exec.get("stockAlertTotal"));
        payload.put("stockAlertsSample", exec.get("stockAlerts"));
        payload.put("lastSyncedAt", exec.get("lastSyncedAt"));

        List<DrillLink> links = List.of(
                new DrillLink("查看 " + actualMonth + " 滚动明细表", "/reports/rpt_contract_scroll?month=" + enc(actualMonth)),
                new DrillLink("返回经营看板", "/")
        );
        return new ToolResult("get_executive_overview", "经营看板总览",
                actualMonth + " 月经营总览", payload, links);
    }

    private ToolResult runContractScroll(JsonNode args) {
        String month = optText(args, "month", latestMonth());
        String custKw = optText(args, "customerKeyword", "");
        String contractNo = optText(args, "contractNo", "");
        boolean onlyNeg = args.path("onlyNegativeStock").asBoolean(false);
        boolean onlyDebt = args.path("onlyDebt").asBoolean(false);
        String sortBy = optText(args, "sortBy", onlyNeg ? "end_black_stock" : "end_total_debt");
        int limit = clamp(args.path("limit").asInt(12), 1, 20);

        ContractScrollService.ScrollReportView view = contractScrollService.computeView(Map.of("month", month), 1, 10000);
        List<Map<String, Object>> allRows = view.rows() == null ? List.of() : view.rows();

        // 客户实体消歧
        EntityResolver.Resolution res = null;
        Set<String> allowedCustomers = null;
        if (!custKw.isBlank()) {
            List<String> universe = allRows.stream()
                    .map(r -> str(r.get("customer_name")))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCustomers = new HashSet<>(res.matched());
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> r : allRows) {
            if (allowedCustomers != null && !allowedCustomers.contains(str(r.get("customer_name")))) continue;
            if (!contractNo.isBlank()) {
                String cno = str(r.get("contract_no")).toLowerCase(Locale.ROOT);
                String pno = str(r.get("u9_project_no")).toLowerCase(Locale.ROOT);
                String kw = contractNo.toLowerCase(Locale.ROOT);
                if (!cno.contains(kw) && !pno.contains(kw)) continue;
            }
            if (onlyNeg) {
                double bs = num(r.get("end_black_stock"));
                double ws = num(r.get("end_white_stock"));
                if (bs >= -0.001 && ws >= -0.001) continue;
            }
            if (onlyDebt && num(r.get("end_total_debt")) <= 0.01) continue;
            filtered.add(r);
        }

        // Java 端精确计算筛选后合计（禁止大模型自己求和）
        double sumWhiteShip = 0, sumWhiteIn = 0, sumBlackIn = 0, sumInv = 0, sumPay = 0;
        double sumEndDebt = 0, sumInvUnpaid = 0, sumShipUninv = 0, sumBlackStock = 0, sumWhiteStock = 0;
        for (Map<String, Object> r : filtered) {
            sumBlackIn += num(r.get("cur_black_in"));
            sumWhiteIn += num(r.get("cur_white_in"));
            sumWhiteShip += num(r.get("cur_white_ship"));
            sumInv += num(r.get("cur_inv_amt"));
            sumPay += num(r.get("cur_pay_amt"));
            sumEndDebt += num(r.get("end_total_debt"));
            sumInvUnpaid += num(r.get("end_inv_unpaid_amt"));
            sumShipUninv += num(r.get("end_ship_uninv_amt"));
            sumBlackStock += num(r.get("end_black_stock"));
            sumWhiteStock += num(r.get("end_white_stock"));
        }

        boolean asc = onlyNeg && ("end_black_stock".equals(sortBy) || "end_white_stock".equals(sortBy));
        filtered.sort((a, b) -> {
            double va = num(a.get(sortBy));
            double vb = num(b.get(sortBy));
            return asc ? Double.compare(va, vb) : Double.compare(vb, va);
        });

        List<Map<String, Object>> topRows = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, filtered.size()); i++) {
            Map<String, Object> r = filtered.get(i);
            Map<String, Object> slim = new LinkedHashMap<>();
            slim.put("contract_no", str(r.get("contract_no")));
            slim.put("customer_name", str(r.get("customer_name")));
            slim.put("product_name", str(r.get("product_name")));
            slim.put("cur_black_in_ton", round2(num(r.get("cur_black_in"))));
            slim.put("cur_white_in_ton", round2(num(r.get("cur_white_in"))));
            slim.put("cur_white_ship_ton", round2(num(r.get("cur_white_ship"))));
            slim.put("end_black_stock_ton", round2(num(r.get("end_black_stock"))));
            slim.put("end_white_stock_ton", round2(num(r.get("end_white_stock"))));
            slim.put("cur_inv_amt_yuan", round2(num(r.get("cur_inv_amt"))));
            slim.put("cur_pay_amt_yuan", round2(num(r.get("cur_pay_amt"))));
            slim.put("end_inv_unpaid_yuan", round2(num(r.get("end_inv_unpaid_amt"))));
            slim.put("end_ship_uninv_yuan", round2(num(r.get("end_ship_uninv_amt"))));
            slim.put("end_total_debt_yuan", round2(num(r.get("end_total_debt"))));
            topRows.add(slim);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matchedRows", filtered.size());
        summary.put("cur_black_in_ton", round2(sumBlackIn));
        summary.put("cur_white_in_ton", round2(sumWhiteIn));
        summary.put("cur_white_ship_ton", round2(sumWhiteShip));
        summary.put("cur_inv_amt_yuan", round2(sumInv));
        summary.put("cur_pay_amt_yuan", round2(sumPay));
        summary.put("end_total_debt_yuan", round2(sumEndDebt));
        summary.put("end_inv_unpaid_yuan", round2(sumInvUnpaid));
        summary.put("end_ship_uninv_yuan", round2(sumShipUninv));
        summary.put("end_black_stock_ton", round2(sumBlackStock));
        summary.put("end_white_stock_ton", round2(sumWhiteStock));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("month", view.month());
        payload.put("lastSyncedAt", view.lastSyncedAt());
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(),
                    "matched", res.matched(),
                    "ambiguous", res.ambiguous(),
                    "suggestions", res.suggestions()
            ));
        }
        payload.put("summary", summary);
        payload.put("topRows", topRows);

        StringBuilder qs = new StringBuilder("month=").append(enc(view.month()));
        if (res != null && res.matched().size() == 1) {
            qs.append("&customer=").append(enc(res.matched().get(0)));
        } else if (!custKw.isBlank()) {
            qs.append("&customer=").append(enc(custKw));
        }
        if (!contractNo.isBlank()) qs.append("&contractNo=").append(enc(contractNo));

        List<DrillLink> links = List.of(
                new DrillLink("打开 " + view.month() + " 滚动统计明细表", "/reports/rpt_contract_scroll?" + qs)
        );
        return new ToolResult("query_contract_scroll", "销售合同滚动统计",
                view.month() + " 月匹配 " + filtered.size() + " 条合同行", payload, links);
    }

    private ToolResult runCompareMonths(JsonNode args) {
        String monthA = optText(args, "monthA", YearMonth.now().minusMonths(1).toString());
        String monthB = optText(args, "monthB", latestMonth());
        String custKw = optText(args, "customerKeyword", "");
        String metric = optText(args, "metric", "debt_change");
        int limit = clamp(args.path("limit").asInt(8), 1, 15);

        ContractScrollService.ScrollReportView viewA = contractScrollService.computeView(Map.of("month", monthA), 1, 10000);
        ContractScrollService.ScrollReportView viewB = contractScrollService.computeView(Map.of("month", monthB), 1, 10000);

        Map<String, double[]> aggA = aggregateByCustomer(viewA.rows());
        Map<String, double[]> aggB = aggregateByCustomer(viewB.rows());
        Set<String> allCusts = new LinkedHashSet<>();
        allCusts.addAll(aggA.keySet());
        allCusts.addAll(aggB.keySet());

        EntityResolver.Resolution res = null;
        if (!custKw.isBlank()) {
            res = EntityResolver.resolve(custKw, allCusts);
            allCusts = new LinkedHashSet<>(res.matched());
        }

        double[] totA = new double[4]; // ship, inv, pay, debt
        double[] totB = new double[4];
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String c : allCusts) {
            double[] a = aggA.getOrDefault(c, new double[4]);
            double[] b = aggB.getOrDefault(c, new double[4]);
            for (int i = 0; i < 4; i++) {
                totA[i] += a[i];
                totB[i] += b[i];
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("customer_name", c);
            item.put("ship_a_ton", round2(a[0]));
            item.put("ship_b_ton", round2(b[0]));
            item.put("ship_diff_ton", round2(b[0] - a[0]));
            item.put("inv_a_yuan", round2(a[1]));
            item.put("inv_b_yuan", round2(b[1]));
            item.put("pay_a_yuan", round2(a[2]));
            item.put("pay_b_yuan", round2(b[2]));
            item.put("pay_diff_yuan", round2(b[2] - a[2]));
            item.put("debt_a_yuan", round2(a[3]));
            item.put("debt_b_yuan", round2(b[3]));
            item.put("debt_diff_yuan", round2(b[3] - a[3]));
            diffs.add(item);
        }

        String sortField = switch (metric) {
            case "ship_change" -> "ship_diff_ton";
            case "pay_change" -> "pay_diff_yuan";
            default -> "debt_diff_yuan";
        };
        diffs.sort((x, y) -> Double.compare(Math.abs(num(y.get(sortField))), Math.abs(num(x.get(sortField)))));

        Map<String, Object> totalCompare = new LinkedHashMap<>();
        totalCompare.put("white_ship_ton", comparePair(totA[0], totB[0]));
        totalCompare.put("invoice_amt_yuan", comparePair(totA[1], totB[1]));
        totalCompare.put("payment_amt_yuan", comparePair(totA[2], totB[2]));
        totalCompare.put("end_total_debt_yuan", comparePair(totA[3], totB[3]));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("monthA", viewA.month());
        payload.put("monthB", viewB.month());
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        payload.put("totalCompare", totalCompare);
        payload.put("topMovers", diffs.stream().limit(limit).toList());

        List<DrillLink> links = List.of(
                new DrillLink("查看 " + viewB.month() + " 滚动报表", "/reports/rpt_contract_scroll?month=" + enc(viewB.month())),
                new DrillLink("查看 " + viewA.month() + " 滚动报表", "/reports/rpt_contract_scroll?month=" + enc(viewA.month()))
        );
        return new ToolResult("compare_contract_months", "跨月经营对比",
                viewA.month() + " vs " + viewB.month(), payload, links);
    }

    private ToolResult runHangReport(JsonNode args) {
        String reportType = optText(args, "reportType", "daily");
        boolean isDiff = "weight_diff".equalsIgnoreCase(reportType);
        String reportCode = isDiff ? "rpt_white_weight_diff" : "rpt_hang_complete";
        String month = optText(args, "month", "");
        String startDate = optText(args, "startDate", "");
        String endDate = optText(args, "endDate", "");
        String custKw = optText(args, "customerKeyword", "");
        boolean onlyAbnormal = args.path("onlyAbnormal").asBoolean(false);
        int limit = clamp(args.path("limit").asInt(12), 1, 20);

        ReportDef report = reportRepo.findById(reportCode).orElse(null);
        if (report == null) {
            return new ToolResult("query_hang_report", displayLabelOf("query_hang_report"), "报表不存在",
                    Map.of("error", "未找到报表: " + reportCode), List.of());
        }

        // 默认查近 30 天或当月，防止全表扫描无意义
        if (month.isBlank() && startDate.isBlank() && endDate.isBlank()) {
            if (isDiff) {
                month = latestMonth();
            } else {
                LocalDate today = LocalDate.now();
                startDate = today.minusDays(14).toString();
                endDate = today.toString();
            }
        }
        if (!month.isBlank() && startDate.isBlank() && endDate.isBlank()) {
            try {
                YearMonth ym = YearMonth.parse(month);
                startDate = ym.atDay(1).toString();
                endDate = ym.atEndOfMonth().toString();
            } catch (Exception ignored) {}
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        if (!startDate.isBlank() || !endDate.isBlank()) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (!startDate.isBlank()) range.put("start", startDate);
            if (!endDate.isBlank()) range.put("end", endDate);
            filters.put("endImmersionTime", range);
        }
        if (isDiff && onlyAbnormal) {
            filters.put("diff_flag", "不一致");
        }

        ReportQueryEngine.QueryResult qr = queryEngine.query(report, new ReportQueryEngine.QueryRequest(filters, 1, 5000));
        List<Map<String, Object>> rows = qr.rows() == null ? List.of() : qr.rows();

        EntityResolver.Resolution res = null;
        Set<String> allowedCust = null;
        if (!custKw.isBlank()) {
            List<String> universe = rows.stream().map(r -> str(r.get("customer_name"))).filter(s -> !s.isBlank()).distinct().toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCust = new HashSet<>(res.matched());
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        int abnormalCnt = 0;
        double totalWhiteKg = 0;
        double totalBlackKg = 0;
        for (Map<String, Object> r : rows) {
            if (allowedCust != null && !allowedCust.contains(str(r.get("customer_name")))) continue;
            boolean abn = isDiff
                    ? "不一致".equals(str(r.get("diff_flag")))
                    : (!isBlankOrNormal(str(r.get("iron_abnormal"))) || !isBlankOrNormal(str(r.get("zinc_abnormal"))));
            if (abn) abnormalCnt++;
            if (onlyAbnormal && !abn) continue;
            totalWhiteKg += num(r.get("white_weight"));
            totalBlackKg += num(r.get(isDiff ? "mes_black_weight" : "black_weight"));
            filtered.add(r);
        }

        if (isDiff) {
            filtered.sort((a, b) -> Double.compare(
                    Math.abs(num(b.get("diff_complete_kg"))),
                    Math.abs(num(a.get("diff_complete_kg")))
            ));
        }

        List<Map<String, Object>> topRows = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, filtered.size()); i++) {
            Map<String, Object> r = filtered.get(i);
            Map<String, Object> slim = new LinkedHashMap<>();
            slim.put("hang_no", str(r.get("hang_no")));
            slim.put("work_date", str(r.get("work_date")));
            slim.put("customer_name", str(r.get("customer_name")));
            slim.put("product_name", str(r.get("product_name")));
            slim.put("white_weight_kg", round2(num(r.get("white_weight"))));
            if (isDiff) {
                slim.put("u9_complete_kg", round2(num(r.get("u9_complete_kg"))));
                slim.put("diff_complete_kg", round2(num(r.get("diff_complete_kg"))));
                slim.put("u9_status", str(r.get("u9_status")));
                slim.put("diff_flag", str(r.get("diff_flag")));
            } else {
                slim.put("line_name", str(r.get("line_name")));
                slim.put("black_weight_kg", round2(num(r.get("black_weight"))));
                slim.put("iron_loss", r.get("iron_loss"));
                slim.put("iron_abnormal", str(r.get("iron_abnormal")));
                slim.put("zinc_loss", r.get("zinc_loss"));
                slim.put("zinc_abnormal", str(r.get("zinc_abnormal")));
                slim.put("zinc_thickness_avg", r.get("zinc_thickness_avg"));
            }
            topRows.add(slim);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportName", report.getName());
        payload.put("dateRange", startDate + " ~ " + endDate);
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        payload.put("summary", Map.of(
                "matchedHangCount", filtered.size(),
                "abnormalCount", abnormalCnt,
                "totalWhiteWeightKg", round2(totalWhiteKg),
                "totalWhiteWeightTon", round2(totalWhiteKg / 1000.0),
                "totalBlackWeightKg", round2(totalBlackKg)
        ));
        payload.put("topRows", topRows);

        List<DrillLink> links = List.of(
                new DrillLink("打开《" + report.getName() + "》", "/reports/" + reportCode)
        );
        return new ToolResult("query_hang_report", report.getName(),
                startDate + "~" + endDate + " 共 " + filtered.size() + " 挂", payload, links);
    }

    private ToolResult runCustFlow(JsonNode args) {
        String dir = optText(args, "direction", "in");
        boolean isOut = "out".equalsIgnoreCase(dir);
        String reportCode = isOut ? "rpt_cust_month_out" : "rpt_cust_month_in";
        String month = optText(args, "month", latestMonth());
        String custKw = optText(args, "customerKeyword", "");
        int limit = clamp(args.path("limit").asInt(12), 1, 20);

        ReportDef report = reportRepo.findById(reportCode).orElse(null);
        if (report == null) {
            return new ToolResult("query_cust_monthly_flow", "客户月度出入库", "报表不存在",
                    Map.of("error", "未找到报表: " + reportCode), List.of());
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("month", month);
        ReportQueryEngine.QueryResult qr = queryEngine.query(report, new ReportQueryEngine.QueryRequest(filters, 1, 5000));
        List<Map<String, Object>> rows = qr.rows() == null ? List.of() : qr.rows();

        String qtyKey = isOut ? "out_qty" : "in_qty";
        String cntKey = isOut ? "out_cnt" : "in_cnt";

        EntityResolver.Resolution res = null;
        Set<String> allowedCust = null;
        if (!custKw.isBlank()) {
            List<String> universe = rows.stream().map(r -> str(r.get("customer"))).filter(s -> !s.isBlank()).distinct().toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCust = new HashSet<>(res.matched());
        }

        Map<String, double[]> byCust = new LinkedHashMap<>();
        double totalQty = 0;
        long totalCnt = 0;
        for (Map<String, Object> r : rows) {
            String cust = str(r.get("customer"));
            if (cust.isBlank()) cust = "未知客户";
            if (allowedCust != null && !allowedCust.contains(cust)) continue;
            double q = num(r.get(qtyKey));
            double c = num(r.get(cntKey));
            totalQty += q;
            totalCnt += Math.round(c);
            double[] acc = byCust.computeIfAbsent(cust, k -> new double[2]);
            acc[0] += q;
            acc[1] += c;
        }

        List<Map<String, Object>> ranking = byCust.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customer", e.getKey());
                    m.put("qty_ton", round2(e.getValue()[0]));
                    m.put("doc_count", Math.round(e.getValue()[1]));
                    return m;
                }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportName", report.getName());
        payload.put("month", month);
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        payload.put("summary", Map.of(
                "customerCount", byCust.size(),
                "totalQtyTon", round2(totalQty),
                "totalDocCount", totalCnt
        ));
        payload.put("topCustomers", ranking);

        List<DrillLink> links = List.of(
                new DrillLink("打开《" + report.getName() + "》", "/reports/" + reportCode)
        );
        return new ToolResult("query_cust_monthly_flow", report.getName(),
                month + " 共 " + round2(totalQty) + " 吨", payload, links);
    }

    private ToolResult runSyncHealth() {
        List<Map<String, Object>> list = syncService.listStatus();
        long enabled = list.stream().filter(x -> Boolean.TRUE.equals(x.get("enabled"))).count();
        long failed = list.stream().filter(x -> "failed".equals(x.get("lastJobStatus"))).count();
        long totalRows = list.stream().mapToLong(x -> ((Number) x.getOrDefault("recordCount", 0)).longValue()).sum();

        List<Map<String, Object>> tables = list.stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", x.get("name"));
            m.put("datasetCode", x.get("datasetCode"));
            m.put("enabled", x.get("enabled"));
            m.put("recordCount", x.get("recordCount"));
            m.put("lastJobStatus", x.get("lastJobStatus"));
            m.put("lastSyncedAt", x.get("lastSyncedAt"));
            if (x.get("lastJobError") != null) m.put("lastJobError", x.get("lastJobError"));
            return m;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalDatasets", list.size());
        payload.put("enabledCount", enabled);
        payload.put("failedCount", failed);
        payload.put("totalLocalRows", totalRows);
        payload.put("datasets", tables);

        List<DrillLink> links = List.of(
                new DrillLink("前往数据同步", "/sync"),
                new DrillLink("查看同步记录", "/sync-logs")
        );
        return new ToolResult("get_sync_health", "数据同步状态巡检",
                failed > 0 ? failed + " 项同步异常" : "全部 " + list.size() + " 表正常", payload, links);
    }

    private static Map<String, double[]> aggregateByCustomer(List<Map<String, Object>> rows) {
        Map<String, double[]> map = new LinkedHashMap<>();
        if (rows == null) return map;
        for (Map<String, Object> r : rows) {
            String cust = str(r.get("customer_name"));
            if (cust.isBlank()) continue;
            double[] a = map.computeIfAbsent(cust, k -> new double[4]);
            a[0] += num(r.get("cur_white_ship"));
            a[1] += num(r.get("cur_inv_amt"));
            a[2] += num(r.get("cur_pay_amt"));
            a[3] += num(r.get("end_total_debt"));
        }
        return map;
    }

    private static Map<String, Object> comparePair(double a, double b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monthA", round2(a));
        m.put("monthB", round2(b));
        m.put("diff", round2(b - a));
        m.put("deltaPct", Math.abs(a) > 0.001 ? round2(((b - a) / Math.abs(a)) * 100.0) : null);
        return m;
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        if (!required.isEmpty()) params.put("required", required);
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", params
                )
        );
    }

    private static Map<String, Object> prop(String type, String desc) {
        return Map.of("type", type, "description", desc);
    }

    private static Map<String, Object> propEnum(String desc, List<String> values) {
        return Map.of("type", "string", "description", desc, "enum", values);
    }

    private static String latestMonth() {
        String now = YearMonth.now().toString();
        return now.compareTo("2026-10") < 0 ? "2026-10" : now;
    }

    private static String optText(JsonNode node, String field, String def) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return def;
        String v = node.get(field).asText("").trim();
        return v.isEmpty() ? def : v;
    }

    private static boolean isBlankOrNormal(String s) {
        return s == null || s.isBlank() || "正常".equals(s) || "-".equals(s);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
