package com.reporthub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.domain.ReportDef;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.repo.ReportRepo;
import com.reporthub.service.ContractScrollService;
import com.reporthub.service.DashboardService;
import com.reporthub.service.QueryModeService;
import com.reporthub.service.SyncService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
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
    private final QueryModeService queryModeService;
    private final ObjectMapper mapper;

    public AiToolExecutor(DashboardService dashboardService,
                          ContractScrollService contractScrollService,
                          ReportQueryEngine queryEngine,
                          ReportRepo reportRepo,
                          SyncService syncService,
                          QueryModeService queryModeService,
                          ObjectMapper mapper) {
        this.dashboardService = dashboardService;
        this.contractScrollService = contractScrollService;
        this.queryEngine = queryEngine;
        this.reportRepo = reportRepo;
        this.syncService = syncService;
        this.queryModeService = queryModeService;
        this.mapper = mapper;
    }

    public String displayLabelOf(String toolName) {
        return switch (toolName) {
            case "get_executive_overview" -> "经营看板总览";
            case "query_contract_scroll" -> "销售合同滚动统计";
            case "compare_contract_months" -> "跨月经营对比";
            case "query_hang_report" -> "挂次生产与重量差异";
            case "query_cust_monthly_flow" -> "客户月度黑白件出入库";
            case "query_u9_doc_flow" -> "U9 按日出入库与领料流水";
            case "query_u9_inventory" -> "U9 实时仓库现存量";
            case "get_sync_health" -> "数据同步状态巡检";
            default -> toolName;
        };
    }

    /** 标准 OpenAI Function Calling 工具清单 */
    public List<Map<String, Object>> toolDefinitions(boolean includeAdminTools) {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(fn(
                "get_executive_overview",
                "获取指定月份的经营驾驶舱总览：包含当月白件发货(吨)、开票金额(元)、回款金额(元)、期末总欠款(元)、U9实时在制库存(吨)及其环比变化、近6个月趋势、客户欠款Top8与U9负库存预警数。",
                Map.of("month", prop("string", "统计月份，格式 YYYY-MM，例如 2026-11；不填则默认当前最新月份")),
                List.of()
        ));

        Map<String, Object> scrollProps = new LinkedHashMap<>();
        scrollProps.put("month", prop("string", "统计月份，格式 YYYY-MM，默认当前最新月份"));
        scrollProps.put("customerKeyword", prop("string", "客户名称或简称关键词（支持模糊匹配，如“华泰”“顺达”）"));
        scrollProps.put("contractNo", prop("string", "纸质合同号或项目号关键词"));
        scrollProps.put("productKeyword", prop("string", "可选，产品名称关键词（如“钢结构”“支架”）"));
        scrollProps.put("onlyNegativeStock", prop("boolean", "是否仅筛选出现黑件或白件负库存的异常合同"));
        scrollProps.put("onlyDebt", prop("boolean", "是否仅筛选期末总欠款大于0的合同"));
        scrollProps.put("sortBy", propEnum("排序指标", List.of("end_total_debt", "cur_white_ship", "cur_inv_amt", "cur_pay_amt", "end_black_stock", "end_white_stock")));
        scrollProps.put("limit", prop("integer", "返回明细条数上限，默认 12，最大 20"));
        tools.add(fn(
                "query_contract_scroll",
                "查询《销售合同滚动统计报表》明细与汇总：支持按客户简称(自动模糊匹配)、合同号、产品名称、仅看欠款、仅看黑白件负库存筛选，并由后端计算合计值与Top排序。",
                scrollProps,
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

        Map<String, Object> hangProps = new LinkedHashMap<>();
        hangProps.put("reportType", propEnum("报表类型：daily=挂次日报表，weight_diff=白件重量差异表", List.of("daily", "weight_diff")));
        hangProps.put("month", prop("string", "按月查询，格式 YYYY-MM（与 startDate/endDate 二选一）"));
        hangProps.put("startDate", prop("string", "开始日期 YYYY-MM-DD（查当天时 startDate 和 endDate 均填当天日期）"));
        hangProps.put("endDate", prop("string", "结束日期 YYYY-MM-DD（包含该日全天，查当天会自动实时直查 MES）"));
        hangProps.put("customerKeyword", prop("string", "可选，客户简称关键词"));
        hangProps.put("itemCode", prop("string", "可选，物料编码关键词（如 1104057-0004、1205057-0005）"));
        hangProps.put("productKeyword", prop("string", "可选，产品名称关键词（如 钢结构、电缆沟支架）"));
        hangProps.put("workStatus", propEnum("可选，挂次状态筛选：12=白件已称重，5=已完成，4=已浸锌，3=已称重，2=已酸洗，1=未酸洗", List.of("12", "5", "4", "3", "2", "1")));
        hangProps.put("onlyAbnormal", prop("boolean", "是否仅看异常记录（weight_diff 时为重量不一致；daily 时为铁损或锌耗异常）"));
        hangProps.put("limit", prop("integer", "返回明细条数上限，默认 12，最大 20"));
        tools.add(fn(
                "query_hang_report",
                "查询生产挂次报表（含物料编码 item_code、白件/黑件重量 kg 与吨位）：reportType=daily 查《挂次日报表》，reportType=weight_diff 查《挂次白件重量差异表》；查今日或近3天会自动实时穿透 MES 获取最新挂次。",
                hangProps,
                List.of("reportType")
        ));

        Map<String, Object> flowProps = new LinkedHashMap<>();
        flowProps.put("direction", propEnum("方向：in=黑件入库，out=白件出库", List.of("in", "out")));
        flowProps.put("month", prop("string", "统计月份 YYYY-MM，默认当前最新月"));
        flowProps.put("customerKeyword", prop("string", "可选，客户简称关键词"));
        flowProps.put("itemCode", prop("string", "可选，料号关键词（如 1104057-0004）"));
        flowProps.put("productKeyword", prop("string", "可选，料品名称关键词"));
        flowProps.put("projectNo", prop("string", "可选，项目号/合同号关键词（仅白件出库表有项目号）"));
        flowProps.put("limit", prop("integer", "返回客户/料品排行条数，默认 12，最大 20"));
        tools.add(fn(
                "query_cust_monthly_flow",
                "查询客户月度出入库汇总：direction=in 查《客户月度黑件入库表》(来料吨位)，direction=out 查《客户月度白件出库表》(成品发货吨位)，支持按客户、料号、料品、项目号筛选并自动汇总排行。",
                flowProps,
                List.of("direction")
        ));

        Map<String, Object> docFlowProps = new LinkedHashMap<>();
        docFlowProps.put("flowType", propEnum(
                "单据流水类型：black_in=黑件来料(BF003杂收单)，black_out=黑件领料(BF003生产领料单)，black_all=同时查黑件来料与领料，white_in=白件入库(BF004完工报告)，white_out=白件发货(BF004出货单)，white_all=同时查白件入库与发货，all=全部四类",
                List.of("black_in", "black_out", "black_all", "white_in", "white_out", "white_all", "all")));
        docFlowProps.put("startDate", prop("string", "开始日期 YYYY-MM-DD，例如 2026-11-23"));
        docFlowProps.put("endDate", prop("string", "结束日期 YYYY-MM-DD（含当日全天），例如 2026-11-26"));
        docFlowProps.put("customerKeyword", prop("string", "可选，客户简称关键词"));
        docFlowProps.put("itemCode", prop("string", "可选，料号关键词（如 1205057-0005、1104057-0004）"));
        docFlowProps.put("productKeyword", prop("string", "可选，物料名称关键词（如 三角梁支架5、6、7、电缆沟支架）"));
        docFlowProps.put("projectNo", prop("string", "可选，项目号/合同号关键词"));
        docFlowProps.put("limit", prop("integer", "每类流水返回单据明细条数上限，默认 15，最大 30"));
        tools.add(fn(
                "query_u9_doc_flow",
                "按任意日期区间查询 U9 ERP 真实出入库与生产领料单据流水（黑件来料杂收单、黑件生产领料单、白件完工入库单、白件出货单）：支持按起止日期、客户、料号、物料名称筛选，自动计算汇总吨位、按日小计、按料号汇总与原始单据明细；查包含今日的近期数据会自动实时穿透 U9。",
                docFlowProps,
                List.of("flowType", "startDate", "endDate")
        ));

        Map<String, Object> invProps = new LinkedHashMap<>();
        invProps.put("warehouse", propEnum("仓库筛选：ALL=全部(BF003+BF004)，BF003=黑件客供原材料库，BF004=白件镀锌成品库", List.of("ALL", "BF003", "BF004")));
        invProps.put("customerKeyword", prop("string", "可选，客户名称或简称关键词"));
        invProps.put("itemCode", prop("string", "可选，物料编码关键词（如 1205057-0005、1104001-0001）"));
        invProps.put("productKeyword", prop("string", "可选，物料名称关键词"));
        invProps.put("onlyNegative", prop("boolean", "是否仅查看负库存异常物料"));
        invProps.put("limit", prop("integer", "返回明细条数上限，默认 15，最大 30"));
        tools.add(fn(
                "query_u9_inventory",
                "查询 U9 ERP 当前真实仓库现存量（WhQoh，BF003 黑件库与 BF004 白件库）：支持按仓库、客户、料号、产品名称或仅负库存筛选，返回准确现存吨位与负库存清单。",
                invProps,
                List.of()
        ));

        if (includeAdminTools) {
            tools.add(fn(
                    "get_sync_health",
                    "巡检所有本地同步表的运行状态：查看启用数、本地行数、最近同步时间以及是否存在同步失败的异常表（仅管理员可用）。",
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
            case "query_u9_doc_flow" -> runU9DocFlow(args);
            case "query_u9_inventory" -> runU9Inventory(args);
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
        payload.put("stockSource", exec.get("stockSource"));
        if (exec.get("u9Inventory") != null) {
            payload.put("u9Inventory", exec.get("u9Inventory"));
        }
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
        String prodKw = optText(args, "productKeyword", "");
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
            if (!prodKw.isBlank()) {
                String pn = str(r.get("product_name")).toLowerCase(Locale.ROOT);
                if (!pn.contains(prodKw.toLowerCase(Locale.ROOT))) continue;
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
        String itemCodeKw = optText(args, "itemCode", "");
        String prodKw = optText(args, "productKeyword", "");
        String workStatus = optText(args, "workStatus", "");
        boolean onlyAbnormal = args.path("onlyAbnormal").asBoolean(false);
        int limit = clamp(args.path("limit").asInt(12), 1, 20);

        ReportDef report = reportRepo.findById(reportCode).orElse(null);
        if (report == null) {
            return new ToolResult("query_hang_report", displayLabelOf("query_hang_report"), "报表不存在",
                    Map.of("error", "未找到报表: " + reportCode), List.of());
        }

        // 默认查近 14 天或当月，防止全表扫描无意义
        LocalDate today = LocalDate.now();
        if (month.isBlank() && startDate.isBlank() && endDate.isBlank()) {
            if (isDiff) {
                month = latestMonth();
            } else {
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
        if (!startDate.isBlank() && endDate.isBlank()) endDate = startDate;
        if (startDate.isBlank() && !endDate.isBlank()) startDate = endDate;

        // 对齐 ReportRun.tsx：date-only 结束日必须补 23:59:59，否则 MES hangEndTimeFinish 与本地引擎均不含当天
        Map<String, Object> filters = new LinkedHashMap<>();
        if (!startDate.isBlank() || !endDate.isBlank()) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (!startDate.isBlank()) range.put("start", startDate);
            if (!endDate.isBlank()) range.put("end", normalizeEndDateInclusive(endDate));
            filters.put("endImmersionTime", range);
        }
        if (!workStatus.isBlank()) {
            filters.put("work_status", workStatus);
        }
        if (isDiff && onlyAbnormal) {
            filters.put("diff_flag", "不一致");
        }

        // 当查询范围包含今日且跨度 <= 3 天时，即使全局在本地模式也自动穿透 MES 实时查询，避免当天尚未同步导致 0 挂
        boolean forceRealtime = shouldUseRealtimeForRecentHang(startDate, endDate, today);
        String actualDataMode = (forceRealtime || !queryModeService.isLocal()) ? "realtime" : "local";
        ReportQueryEngine.QueryResult qr;
        try {
            qr = queryEngine.query(report, new ReportQueryEngine.QueryRequest(filters, 1, 5000), forceRealtime);
        } catch (Exception e) {
            if (forceRealtime && queryModeService.isLocal()) {
                qr = queryEngine.query(report, new ReportQueryEngine.QueryRequest(filters, 1, 5000), false);
                actualDataMode = "local";
            } else {
                throw e;
            }
        }
        List<Map<String, Object>> rows = qr.rows() == null ? List.of() : qr.rows();

        // weight_diff 的过滤器 op=month，本地模式会返回整月行；若调用方显式指定了更窄的日区间则补一层日期裁剪
        String startDay10 = day10(startDate);
        String endDay10 = day10(endDate);
        boolean narrowDateClip = isDiff && !startDay10.isEmpty() && !endDay10.isEmpty();

        EntityResolver.Resolution res = null;
        Set<String> allowedCust = null;
        if (!custKw.isBlank()) {
            List<String> universe = rows.stream().map(r -> str(r.get("customer_name"))).filter(s -> !s.isBlank()).distinct().toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCust = new HashSet<>(res.matched());
        }

        String itemKwLower = itemCodeKw.trim().toLowerCase(Locale.ROOT);
        String prodKwLower = prodKw.trim().toLowerCase(Locale.ROOT);

        List<Map<String, Object>> filtered = new ArrayList<>();
        int abnormalCnt = 0;
        double totalWhiteKg = 0;
        double totalBlackKg = 0;
        for (Map<String, Object> r : rows) {
            if (narrowDateClip) {
                String rd = day10(str(r.get("hang_end_time")));
                if (rd.isEmpty()) rd = day10(str(r.get("work_date")));
                if (!rd.isEmpty() && (rd.compareTo(startDay10) < 0 || rd.compareTo(endDay10) > 0)) continue;
            }
            if (allowedCust != null && !allowedCust.contains(str(r.get("customer_name")))) continue;
            if (!itemKwLower.isEmpty()) {
                String ic = str(r.get("item_code")).toLowerCase(Locale.ROOT);
                String pn = str(r.get("product_no")).toLowerCase(Locale.ROOT);
                if (!ic.contains(itemKwLower) && !pn.contains(itemKwLower)) continue;
            }
            if (!prodKwLower.isEmpty()) {
                String pName = str(r.get("product_name")).toLowerCase(Locale.ROOT);
                if (!pName.contains(prodKwLower)) continue;
            }
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
            slim.put("prod_status", str(r.get("prod_status")));
            slim.put("customer_name", str(r.get("customer_name")));
            slim.put("item_code", str(r.get("item_code")));
            slim.put("product_name", str(r.get("product_name")));
            String cno = str(r.get("contract_no"));
            if (!cno.isEmpty()) slim.put("contract_no", cno);
            slim.put("white_weight_kg", round2(num(r.get("white_weight"))));
            if (isDiff) {
                slim.put("u9_complete_kg", round2(num(r.get("u9_complete_kg"))));
                slim.put("diff_complete_kg", round2(num(r.get("diff_complete_kg"))));
                slim.put("u9_status", str(r.get("u9_status")));
                slim.put("diff_flag", str(r.get("diff_flag")));
            } else {
                slim.put("line_name", str(r.get("line_name")));
                slim.put("hang_end_time", str(r.get("hang_end_time")));
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
        payload.put("dataMode", actualDataMode);
        if ("local".equals(actualDataMode) && !endDay10.isEmpty() && endDay10.compareTo(today.toString()) >= 0) {
            payload.put("dataNote", "当前为本地同步库历史数据（每日凌晨定时同步），如需当日最新挂次请缩小日期至近 3 天内或切换「实时聚合」模式。");
        }
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("matchedHangCount", filtered.size());
        summary.put("abnormalCount", abnormalCnt);
        summary.put("totalWhiteWeightKg", round2(totalWhiteKg));
        summary.put("totalWhiteWeightTon", round2(totalWhiteKg / 1000.0));
        summary.put("totalBlackWeightKg", round2(totalBlackKg));
        summary.put("totalBlackWeightTon", round2(totalBlackKg / 1000.0));
        payload.put("summary", summary);
        payload.put("topRows", topRows);

        List<DrillLink> links = List.of(
                new DrillLink("打开《" + report.getName() + "》", "/reports/" + reportCode)
        );
        String tag = "realtime".equals(actualDataMode) ? " · 实时" : "";
        return new ToolResult("query_hang_report", report.getName(),
                startDate + "~" + endDate + " 共 " + filtered.size() + " 挂" + tag, payload, links);
    }

    private ToolResult runCustFlow(JsonNode args) {
        String dir = optText(args, "direction", "in");
        boolean isOut = "out".equalsIgnoreCase(dir);
        String reportCode = isOut ? "rpt_cust_month_out" : "rpt_cust_month_in";
        String month = optText(args, "month", latestMonth());
        String custKw = optText(args, "customerKeyword", "");
        String itemCodeKw = optText(args, "itemCode", "");
        String prodKw = optText(args, "productKeyword", "");
        String projKw = optText(args, "projectNo", "");
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

        String itemKwLower = itemCodeKw.trim().toLowerCase(Locale.ROOT);
        String prodKwLower = prodKw.trim().toLowerCase(Locale.ROOT);
        String projKwLower = projKw.trim().toLowerCase(Locale.ROOT);

        Map<String, double[]> byCust = new LinkedHashMap<>();
        List<Map<String, Object>> matchedItems = new ArrayList<>();
        double totalQty = 0;
        long totalCnt = 0;
        for (Map<String, Object> r : rows) {
            String cust = str(r.get("customer"));
            if (cust.isBlank()) cust = "未知客户";
            if (allowedCust != null && !allowedCust.contains(cust)) continue;
            String ic = str(r.get("item_code"));
            String prod = str(r.get("product"));
            String proj = str(r.get("project_no"));
            if (!itemKwLower.isEmpty() && !ic.toLowerCase(Locale.ROOT).contains(itemKwLower)) continue;
            if (!prodKwLower.isEmpty() && !prod.toLowerCase(Locale.ROOT).contains(prodKwLower)) continue;
            if (!projKwLower.isEmpty() && !proj.toLowerCase(Locale.ROOT).contains(projKwLower)) continue;

            double q = num(r.get(qtyKey));
            double c = num(r.get(cntKey));
            totalQty += q;
            totalCnt += Math.round(c);
            double[] acc = byCust.computeIfAbsent(cust, k -> new double[2]);
            acc[0] += q;
            acc[1] += c;

            Map<String, Object> itemRow = new LinkedHashMap<>();
            itemRow.put("customer", cust);
            if (!proj.isEmpty()) itemRow.put("project_no", proj);
            itemRow.put("item_code", ic);
            itemRow.put("product", prod);
            itemRow.put("qty_ton", round2(q));
            itemRow.put("doc_count", Math.round(c));
            matchedItems.add(itemRow);
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

        matchedItems.sort((a, b) -> Double.compare(num(b.get("qty_ton")), num(a.get("qty_ton"))));
        List<Map<String, Object>> topItems = matchedItems.stream().limit(limit).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportName", report.getName());
        payload.put("month", month);
        payload.put("dataMode", queryModeService.mode());
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        payload.put("summary", Map.of(
                "customerCount", byCust.size(),
                "itemRowCount", matchedItems.size(),
                "totalQtyTon", round2(totalQty),
                "totalDocCount", totalCnt
        ));
        payload.put("topCustomers", ranking);
        payload.put("topItems", topItems);

        List<DrillLink> links = List.of(
                new DrillLink("打开《" + report.getName() + "》", "/reports/" + reportCode)
        );
        return new ToolResult("query_cust_monthly_flow", report.getName(),
                month + " 共 " + round2(totalQty) + " 吨", payload, links);
    }

    private ToolResult runU9DocFlow(JsonNode args) {
        String flowType = optText(args, "flowType", "black_all").toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        String startDate = day10(optText(args, "startDate", ""));
        String endDate = day10(optText(args, "endDate", ""));
        if (startDate.isEmpty() && endDate.isEmpty()) {
            startDate = today.minusDays(7).toString();
            endDate = today.toString();
        } else if (startDate.isEmpty()) {
            startDate = endDate;
        } else if (endDate.isEmpty()) {
            endDate = startDate;
        }
        String custKw = optText(args, "customerKeyword", "");
        String itemCodeKw = optText(args, "itemCode", "");
        String prodKw = optText(args, "productKeyword", "");
        String projKw = optText(args, "projectNo", "");
        int limit = clamp(args.path("limit").asInt(15), 1, 30);

        List<String> targetTypes = switch (flowType) {
            case "black_in" -> List.of("black_in");
            case "black_out" -> List.of("black_out");
            case "white_in" -> List.of("white_in");
            case "white_out" -> List.of("white_out");
            case "white_all" -> List.of("white_in", "white_out");
            case "all" -> List.of("black_in", "black_out", "white_in", "white_out");
            default -> List.of("black_in", "black_out");
        };

        boolean forceRealtime = shouldUseRealtimeForRecentU9Flow(startDate, endDate, today);

        Map<String, Object> sections = new LinkedHashMap<>();
        List<String> summaryParts = new ArrayList<>();
        Set<String> modes = new LinkedHashSet<>();

        for (String ft : targetTypes) {
            String dsCode = datasetOfFlowType(ft);
            ContractScrollService.U9InventoryRawData raw = contractScrollService.loadDatasetRawData(dsCode, forceRealtime);
            modes.add(raw.sourceMode());
            Map<String, Object> sec = summarizeU9DocFlow(
                    ft, raw.rows(), startDate, endDate, custKw, itemCodeKw, prodKw, projKw, limit, raw.sourceMode(), raw.asOf());
            sections.put(ft, sec);
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) sec.get("summary");
            summaryParts.add(flowTypeLabel(ft) + " " + sm.get("totalQtyTon") + " 吨(" + sm.get("docLineCount") + "笔)");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dateRange", startDate + " ~ " + endDate);
        payload.put("flowType", flowType);
        payload.put("dataMode", modes.contains("realtime") ? "realtime" : "local");
        payload.put("flows", sections);

        // 若同时查入+出，自动附带净变动
        if (sections.containsKey("black_in") && sections.containsKey("black_out")) {
            double inTon = extractFlowTotalTon(sections.get("black_in"));
            double outTon = extractFlowTotalTon(sections.get("black_out"));
            payload.put("blackNetChangeTon", round2(inTon - outTon));
        }
        if (sections.containsKey("white_in") && sections.containsKey("white_out")) {
            double inTon = extractFlowTotalTon(sections.get("white_in"));
            double outTon = extractFlowTotalTon(sections.get("white_out"));
            payload.put("whiteNetChangeTon", round2(inTon - outTon));
        }

        List<DrillLink> links = new ArrayList<>();
        String ym = startDate.length() >= 7 ? startDate.substring(0, 7) : latestMonth();
        links.add(new DrillLink("查看 " + ym + " 合同滚动明细", "/reports/rpt_contract_scroll?month=" + enc(ym)));
        if (targetTypes.contains("black_in")) {
            links.add(new DrillLink("打开《客户月度黑件入库表》", "/reports/rpt_cust_month_in"));
        }
        if (targetTypes.contains("white_out")) {
            links.add(new DrillLink("打开《客户月度白件出库表》", "/reports/rpt_cust_month_out"));
        }

        return new ToolResult("query_u9_doc_flow", "U9 按日出入库与领料流水",
                startDate + "~" + endDate + " " + String.join(" / ", summaryParts), payload, links);
    }

    static Map<String, Object> summarizeU9DocFlow(String flowType,
                                                  List<ObjectNode> rawRows,
                                                  String startDate,
                                                  String endDate,
                                                  String custKw,
                                                  String itemCodeKw,
                                                  String prodKw,
                                                  String projKw,
                                                  int limit,
                                                  String sourceMode,
                                                  String asOf) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (ObjectNode n : rawRows) {
            String bizDate = day10(extractFlowDate(flowType, n));
            if (bizDate.isEmpty()) continue;
            if (!startDate.isEmpty() && bizDate.compareTo(startDate) < 0) continue;
            if (!endDate.isEmpty() && bizDate.compareTo(endDate) > 0) continue;

            double qty = extractFlowQty(flowType, n);
            if (Math.abs(qty) < 1e-6) continue;

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("date", bizDate);
            rec.put("doc_no", extractFlowDocNo(flowType, n));
            rec.put("customer_name", extractFlowCustomer(flowType, n));
            rec.put("item_code", extractFlowItemCode(flowType, n));
            rec.put("product_name", extractFlowItemName(flowType, n));
            String proj = extractFlowProject(flowType, n);
            if (!proj.isEmpty()) rec.put("project_no", proj);
            rec.put("qty_ton", round3(qty));
            normalized.add(rec);
        }

        EntityResolver.Resolution res = null;
        Set<String> allowedCust = null;
        if (custKw != null && !custKw.isBlank()) {
            List<String> universe = normalized.stream()
                    .map(r -> str(r.get("customer_name")))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCust = new HashSet<>(res.matched());
        }

        String itemKwLower = itemCodeKw == null ? "" : itemCodeKw.trim().toLowerCase(Locale.ROOT);
        String prodKwLower = prodKw == null ? "" : prodKw.trim().toLowerCase(Locale.ROOT);
        String projKwLower = projKw == null ? "" : projKw.trim().toLowerCase(Locale.ROOT);

        List<Map<String, Object>> filtered = new ArrayList<>();
        double totalTon = 0.0;
        Map<String, double[]> byDate = new TreeMap<>();
        Map<String, double[]> byItem = new LinkedHashMap<>();
        Map<String, Map<String, String>> byItemMeta = new LinkedHashMap<>();

        for (Map<String, Object> r : normalized) {
            if (allowedCust != null && !allowedCust.contains(str(r.get("customer_name")))) continue;
            String ic = str(r.get("item_code"));
            String pn = str(r.get("product_name"));
            String prj = str(r.get("project_no"));
            if (!itemKwLower.isEmpty() && !ic.toLowerCase(Locale.ROOT).contains(itemKwLower)) continue;
            if (!prodKwLower.isEmpty()
                    && !pn.toLowerCase(Locale.ROOT).contains(prodKwLower)
                    && !ic.toLowerCase(Locale.ROOT).contains(prodKwLower)) continue;
            if (!projKwLower.isEmpty() && !prj.toLowerCase(Locale.ROOT).contains(projKwLower)) continue;

            double q = num(r.get("qty_ton"));
            totalTon += q;
            filtered.add(r);

            String dt = str(r.get("date"));
            double[] dAcc = byDate.computeIfAbsent(dt, k -> new double[2]);
            dAcc[0] += q;
            dAcc[1] += 1;

            String itemKey = ic + "||" + pn + "||" + str(r.get("customer_name"));
            double[] iAcc = byItem.computeIfAbsent(itemKey, k -> new double[2]);
            iAcc[0] += q;
            iAcc[1] += 1;
            byItemMeta.putIfAbsent(itemKey, Map.of(
                    "item_code", ic,
                    "product_name", pn,
                    "customer_name", str(r.get("customer_name"))
            ));
        }

        List<Map<String, Object>> dailyBreakdown = byDate.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("qty_ton", round3(e.getValue()[0]));
            m.put("doc_count", Math.round(e.getValue()[1]));
            return m;
        }).toList();

        List<Map<String, Object>> itemSummary = byItem.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .map(e -> {
                    Map<String, String> meta = byItemMeta.get(e.getKey());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("item_code", meta.get("item_code"));
                    m.put("product_name", meta.get("product_name"));
                    m.put("customer_name", meta.get("customer_name"));
                    m.put("qty_ton", round3(e.getValue()[0]));
                    m.put("doc_count", Math.round(e.getValue()[1]));
                    return m;
                }).toList();

        filtered.sort(Comparator.comparing((Map<String, Object> m) -> str(m.get("date")))
                .thenComparing(m -> str(m.get("doc_no"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", flowTypeLabel(flowType));
        out.put("dataset", datasetOfFlowType(flowType));
        out.put("dataMode", sourceMode);
        out.put("asOf", asOf);
        if (res != null) {
            out.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        out.put("summary", Map.of(
                "docLineCount", filtered.size(),
                "totalQtyTon", round3(totalTon)
        ));
        out.put("dailyBreakdown", dailyBreakdown);
        out.put("byItem", itemSummary);
        out.put("topDocs", filtered.stream().limit(limit).toList());
        return out;
    }

    static boolean shouldUseRealtimeForRecentU9Flow(String startDate, String endDate, LocalDate today) {
        String s10 = day10(startDate);
        String e10 = day10(endDate);
        if (s10.isEmpty() || e10.isEmpty()) return false;
        try {
            LocalDate sd = LocalDate.parse(s10);
            LocalDate ed = LocalDate.parse(e10);
            if (ed.isBefore(today)) return false;
            long spanDays = ChronoUnit.DAYS.between(sd, ed);
            return spanDays >= 0 && spanDays <= 7;
        } catch (Exception e) {
            return false;
        }
    }

    private static String datasetOfFlowType(String ft) {
        return switch (ft) {
            case "black_in" -> "ds_u9c_misc_rcv_l";
            case "black_out" -> "ds_u9c_issue_bf003";
            case "white_in" -> "ds_u9c_complete_bf004";
            case "white_out" -> "ds_u9c_ship_l";
            default -> "ds_u9c_misc_rcv_l";
        };
    }

    private static String flowTypeLabel(String ft) {
        return switch (ft) {
            case "black_in" -> "BF003 黑件来料(杂收单)";
            case "black_out" -> "BF003 黑件领料(生产领料单)";
            case "white_in" -> "BF004 白件入库(完工报告)";
            case "white_out" -> "BF004 白件发货(出货单)";
            default -> ft;
        };
    }

    private static String extractFlowDate(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in" -> pathText(n, "MiscRcvTrans", "BusinessDate");
            case "black_out" -> pathText(n, "IssueDoc", "BusinessDate");
            case "white_in" -> pathText(n, "BusinessDate");
            case "white_out" -> pathText(n, "Ship", "BusinessDate");
            default -> "";
        };
    }

    private static String extractFlowDocNo(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in" -> pathText(n, "MiscRcvTrans", "DocNo");
            case "black_out" -> pathText(n, "IssueDoc", "DocNo");
            case "white_in" -> pathText(n, "DocNo");
            case "white_out" -> pathText(n, "Ship", "DocNo");
            default -> "";
        };
    }

    private static double extractFlowQty(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in" -> num(pathText(n, "StoreUOMQty"));
            case "black_out" -> {
                String v = pathText(n, "IssuedQty");
                yield num(v.isEmpty() ? pathText(n, "IssueQty") : v);
            }
            case "white_in" -> {
                String v = pathText(n, "RcvQtyByWhUOM");
                yield num(v.isEmpty() ? pathText(n, "CompleteQty") : v);
            }
            case "white_out" -> num(pathText(n, "ShipQtyTUAmount"));
            default -> 0.0;
        };
    }

    private static String extractFlowItemCode(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in", "white_out" -> pathText(n, "ItemInfo", "ItemCode");
            case "black_out", "white_in" -> pathText(n, "Item", "Code");
            default -> "";
        };
    }

    private static String extractFlowItemName(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in", "white_out" -> pathText(n, "ItemInfo", "ItemName");
            case "black_out", "white_in" -> pathText(n, "Item", "Name");
            default -> "";
        };
    }

    private static String extractFlowCustomer(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_in", "white_out" -> pathText(n, "ItemInfo", "ItemID", "MainItemCategory", "Name");
            case "black_out", "white_in" -> pathText(n, "Item", "MainItemCategory", "Name");
            default -> "";
        };
    }

    private static String extractFlowProject(String ft, ObjectNode n) {
        return switch (ft) {
            case "black_out" -> {
                String p = pathText(n, "MO", "Project", "Code");
                yield p.isEmpty() ? pathText(n, "Project", "Code") : p;
            }
            case "white_in" -> {
                String p = pathText(n, "Project", "Code");
                yield p.isEmpty() ? pathText(n, "MO", "Project", "Code") : p;
            }
            default -> pathText(n, "Project", "Code");
        };
    }

    @SuppressWarnings("unchecked")
    private static double extractFlowTotalTon(Object sectionObj) {
        if (!(sectionObj instanceof Map<?, ?> sec)) return 0.0;
        Object sm = sec.get("summary");
        if (!(sm instanceof Map<?, ?> m)) return 0.0;
        return num(m.get("totalQtyTon"));
    }

    private static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private ToolResult runU9Inventory(JsonNode args) {
        String whFilter = optText(args, "warehouse", "ALL").toUpperCase(Locale.ROOT);
        String custKw = optText(args, "customerKeyword", "");
        String itemCodeKw = optText(args, "itemCode", "");
        String prodKw = optText(args, "productKeyword", "");
        boolean onlyNeg = args.path("onlyNegative").asBoolean(false);
        int limit = clamp(args.path("limit").asInt(15), 1, 30);

        ContractScrollService.U9InventoryRawData raw = contractScrollService.loadU9InventoryRawData(false);
        List<Map<String, Object>> allItems = new ArrayList<>();
        for (ObjectNode n : raw.rows()) {
            String wh = pathText(n, "Wh", "Code").toUpperCase(Locale.ROOT);
            if (!"BF003".equals(wh) && !"BF004".equals(wh)) continue;
            if (!"ALL".equals(whFilter) && !whFilter.equals(wh)) continue;
            String uom = pathText(n, "StoreUOM", "Name");
            double rawQty = num(n.path("StoreQty").asText("0"));
            Double tonQty = toInventoryTons(rawQty, uom);
            if (tonQty == null) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("wh_code", wh);
            item.put("wh_name", pathText(n, "Wh", "Name"));
            item.put("customer_name", pathText(n, "ItemInfo", "ItemID", "MainItemCategory", "Name"));
            item.put("item_code", pathText(n, "ItemInfo", "ItemCode"));
            item.put("product_name", pathText(n, "ItemInfo", "ItemName"));
            String proj = pathText(n, "Project", "Code");
            if (!proj.isEmpty()) item.put("project_no", proj);
            item.put("qty_ton", round2(tonQty));
            allItems.add(item);
        }

        EntityResolver.Resolution res = null;
        Set<String> allowedCust = null;
        if (!custKw.isBlank()) {
            List<String> universe = allItems.stream()
                    .map(r -> str(r.get("customer_name")))
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            res = EntityResolver.resolve(custKw, universe);
            allowedCust = new HashSet<>(res.matched());
        }

        String itemKwLower = itemCodeKw.trim().toLowerCase(Locale.ROOT);
        String prodKwLower = prodKw.trim().toLowerCase(Locale.ROOT);

        List<Map<String, Object>> filtered = new ArrayList<>();
        double blackTon = 0, whiteTon = 0;
        int negCnt = 0;
        for (Map<String, Object> item : allItems) {
            if (allowedCust != null && !allowedCust.contains(str(item.get("customer_name")))) continue;
            if (!itemKwLower.isEmpty() && !str(item.get("item_code")).toLowerCase(Locale.ROOT).contains(itemKwLower)) continue;
            if (!prodKwLower.isEmpty() && !str(item.get("product_name")).toLowerCase(Locale.ROOT).contains(prodKwLower)) continue;
            double q = num(item.get("qty_ton"));
            if (onlyNeg && q >= -0.001) continue;
            if (Math.abs(q) < 0.001 && itemKwLower.isEmpty() && custKw.isBlank()) continue;
            if (q < -0.001) negCnt++;
            if ("BF003".equals(item.get("wh_code"))) blackTon += q;
            else whiteTon += q;
            filtered.add(item);
        }

        if (onlyNeg) {
            filtered.sort(Comparator.comparingDouble(m -> num(m.get("qty_ton"))));
        } else {
            filtered.sort((a, b) -> Double.compare(Math.abs(num(b.get("qty_ton"))), Math.abs(num(a.get("qty_ton")))));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "U9 ERP WhQoh (BF003 黑件 / BF004 白件)");
        payload.put("dataMode", raw.sourceMode());
        payload.put("asOf", raw.asOf());
        if (res != null) {
            payload.put("customerResolution", Map.of(
                    "query", res.query(), "matched", res.matched(),
                    "ambiguous", res.ambiguous(), "suggestions", res.suggestions()
            ));
        }
        payload.put("summary", Map.of(
                "matchedItemCount", filtered.size(),
                "negativeCount", negCnt,
                "blackStockTon", round2(blackTon),
                "whiteStockTon", round2(whiteTon),
                "totalStockTon", round2(blackTon + whiteTon)
        ));
        payload.put("topItems", filtered.stream().limit(limit).toList());

        List<DrillLink> links = List.of(new DrillLink("返回经营看板查看实时库存", "/"));
        return new ToolResult("query_u9_inventory", "U9 实时仓库现存量",
                "匹配 " + filtered.size() + " 条 · 合计 " + round2(blackTon + whiteTon) + " 吨", payload, links);
    }

    /** 将 YYYY-MM-DD 结束日期补齐 23:59:59，确保 MES 上游与本地引擎均包含结束日全天 */
    static String normalizeEndDateInclusive(String endDate) {
        if (endDate == null) return "";
        String s = endDate.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return s + " 23:59:59";
        }
        return s;
    }

    /** 判断挂次查询是否应自动穿透 MES 实时接口（包含今日且日期跨度 <= 3 天） */
    static boolean shouldUseRealtimeForRecentHang(String startDate, String endDate, LocalDate today) {
        String s10 = day10(startDate);
        String e10 = day10(endDate);
        if (s10.isEmpty() || e10.isEmpty()) return false;
        try {
            LocalDate sd = LocalDate.parse(s10);
            LocalDate ed = LocalDate.parse(e10);
            if (ed.isBefore(today)) return false;
            long spanDays = ChronoUnit.DAYS.between(sd, ed);
            return spanDays >= 0 && spanDays <= 3;
        } catch (Exception e) {
            return false;
        }
    }

    private static String day10(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() >= 10 && t.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}") ? t.substring(0, 10) : "";
    }

    private static String pathText(JsonNode root, String... keys) {
        JsonNode cur = root;
        for (String k : keys) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) return "";
            cur = cur.path(k);
        }
        return cur == null || cur.isMissingNode() || cur.isNull() ? "" : cur.asText("").trim();
    }

    private static Double toInventoryTons(double qty, String uom) {
        String u = uom == null ? "" : uom.trim().toLowerCase(Locale.ROOT);
        if (u.isEmpty() || "吨".equals(u) || "t".equals(u) || "ton".equals(u) || "tons".equals(u)) return qty;
        if ("公斤".equals(u) || "千克".equals(u) || "kg".equals(u)) return qty / 1000.0;
        return null;
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
