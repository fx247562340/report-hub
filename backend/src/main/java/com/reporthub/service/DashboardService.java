package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.config.ReportHubProperties;
import com.reporthub.domain.DashboardWidget;
import com.reporthub.domain.ReportDef;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.repo.DashboardWidgetRepo;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DashboardService {
    private final DashboardWidgetRepo widgetRepo;
    private final ConfigService configService;
    private final ReportQueryEngine engine;
    private final ReportResultCache resultCache;
    private final ReportHubProperties props;
    private final ObjectMapper mapper;

    private volatile CachedDash cache;
    private final Map<String, CompletableFuture<CachedDash>> inflight = new ConcurrentHashMap<>();

    private record CachedDash(List<Map<String, Object>> items, long atMs, boolean partial) {}

    public DashboardService(DashboardWidgetRepo widgetRepo,
                            ConfigService configService,
                            ReportQueryEngine engine,
                            ReportResultCache resultCache,
                            ReportHubProperties props,
                            ObjectMapper mapper) {
        this.widgetRepo = widgetRepo;
        this.configService = configService;
        this.engine = engine;
        this.resultCache = resultCache;
        this.props = props;
        this.mapper = mapper;
    }

    public List<DashboardWidget> listWidgets() {
        return widgetRepo.findAllByOrderBySortAsc();
    }

    public List<DashboardWidget> saveWidgets(List<DashboardWidget> widgets) {
        // 固定 4 格：不足补齐、多余丢弃
        List<DashboardWidget> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DashboardWidget w = (widgets != null && widgets.size() > i && widgets.get(i) != null)
                    ? widgets.get(i) : new DashboardWidget();
            if (w.getId() == null || w.getId().isBlank()) {
                w.setId("dw-slot-" + (i + 1));
            }
            if (w.getTitle() == null || w.getTitle().isBlank()) w.setTitle("指标 " + (i + 1));
            if (w.getReportCode() == null) w.setReportCode("");
            w.setSort(i);
            w.setEnabled(true);
            if (w.getMetric() == null || w.getMetric().isBlank()) w.setMetric("count");
            if (w.getFiltersJson() == null || w.getFiltersJson().isBlank()) w.setFiltersJson("{}");
            if (w.getTimeRange() == null || w.getTimeRange().isBlank()) w.setTimeRange("month");
            String tr = w.getTimeRange().toLowerCase();
            if (!tr.equals("today") && !tr.equals("week") && !tr.equals("month")) w.setTimeRange("month");
            w.setUpdatedAt(java.time.Instant.now());
            out.add(widgetRepo.save(w));
        }
        Set<String> keep = new HashSet<>();
        for (DashboardWidget w : out) keep.add(w.getId());
        widgetRepo.findAll().forEach(w -> {
            if (!keep.contains(w.getId())) widgetRepo.deleteById(w.getId());
        });
        invalidate();
        CompletableFuture.runAsync(() -> {
            try {
                getOrLoad(true);
            } catch (Exception ignored) {
            }
        });
        return out;
    }

    public void invalidate() {
        cache = null;
    }

    /**
     * 首页入口：优先读缓存立刻返回；没有缓存则先给占位并后台算。
     * force=true 时同步算完再返回（保存配置后用）。
     */
    public List<Map<String, Object>> getForHome() {
        CachedDash fresh = freshCache();
        if (fresh != null) {
            boolean incomplete = fresh.partial()
                    || fresh.items().stream().anyMatch(i -> i.get("value") == null && !Boolean.FALSE.equals(i.get("ok")));
            if (incomplete) {
                // count 已出但 sum 还在算 / 上次失败：继续补算
                triggerAsync();
            }
            return decorate(fresh.items(), incomplete || fresh.partial());
        }
        if (cache != null) {
            triggerAsync();
            return decorate(cache.items(), true);
        }
        List<Map<String, Object>> shells = shells();
        if (shells.isEmpty()) return shells;
        triggerAsync();
        return decorate(shells, true);
    }

    /** 同步计算（测试 / 保存后） */
    public List<Map<String, Object>> computeNow() {
        CachedDash c = getOrLoad(true);
        return decorate(c.items(), false);
    }

    private List<Map<String, Object>> shells() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardWidget w : enabledWidgets()) {
            Map<String, Object> item = baseItem(w);
            item.put("value", null);
            item.put("ok", true);
            item.put("loading", true);
            out.add(item);
        }
        return out;
    }

    private List<DashboardWidget> enabledWidgets() {
        return listWidgets().stream().filter(w -> Boolean.TRUE.equals(w.getEnabled())).toList();
    }

    private Map<String, Object> baseItem(DashboardWidget w) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", w.getId());
        item.put("title", w.getTitle());
        item.put("metric", w.getMetric());
        item.put("field", w.getField());
        item.put("unit", w.getUnit() == null ? "" : w.getUnit());
        item.put("reportCode", w.getReportCode());
        item.put("sort", w.getSort());
        item.put("timeRange", w.getTimeRange() == null || w.getTimeRange().isBlank() ? "month" : w.getTimeRange());
        Map<String, String> bounds = timeBounds(w.getTimeRange());
        item.put("dateStart", bounds.get("start"));
        item.put("dateEnd", bounds.get("end"));
        try {
            ReportDef report = configService.getReport(w.getReportCode());
            item.put("reportName", report.getName());
            item.put("dateFilterKey", w.getDateFilterKey() == null || w.getDateFilterKey().isBlank()
                    ? findDateFilterKey(report.getFiltersJson()) : w.getDateFilterKey());
        } catch (Exception e) {
            item.put("reportName", w.getReportCode());
        }
        return item;
    }

    private List<Map<String, Object>> decorate(List<Map<String, Object>> items, boolean stale) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Map<String, Object> src : items) {
            Map<String, Object> item = new LinkedHashMap<>(src);
            item.put("stale", stale);
            // 还没算出数值的格子标 loading，前端好区分
            if (item.get("value") == null && Boolean.TRUE.equals(item.get("ok"))) {
                item.put("loading", true);
            }
            out.add(item);
        }
        return out;
    }

    private CachedDash freshCache() {
        CachedDash c = cache;
        if (c == null) return null;
        // partial 结果也先给出去（count 已出、sum 在算）
        long ttl = Math.max(5, props.getEngine().getDashboardTtlSeconds()) * 1000L;
        return (System.currentTimeMillis() - c.atMs() <= ttl) ? c : null;
    }

    private void triggerAsync() {
        if (inflight.containsKey("home")) return;
        inflight.put("home", CompletableFuture.supplyAsync(() -> {
            try {
                CachedDash computed = doCompute();
                cache = computed;
                return computed;
            } catch (Exception e) {
                // 失败也别把 partial 当成终态卡死
                return cache != null ? cache : new CachedDash(shells(), System.currentTimeMillis(), true);
            } finally {
                inflight.remove("home");
            }
        }));
    }

    private CachedDash getOrLoad(boolean sync) {
        CachedDash fresh = freshCache();
        boolean complete = fresh != null && !fresh.partial()
                && fresh.items().stream().noneMatch(i -> i.get("value") == null && !Boolean.FALSE.equals(i.get("ok")));
        if (complete) return fresh;
        if (!sync) {
            CachedDash c = cache;
            return c != null ? c : new CachedDash(shells(), System.currentTimeMillis(), true);
        }
        // 同步等待完整结果（即使已有 partial 缓存）
        CompletableFuture<CachedDash> f = inflight.computeIfAbsent("home", k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        CachedDash computed = doCompute();
                        cache = computed;
                        return computed;
                    } catch (Exception e) {
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    } finally {
                        inflight.remove(k);
                    }
                }));
        try {
            CachedDash r = f.join();
            return r;
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    private CachedDash doCompute() {
        List<DashboardWidget> widgets = enabledWidgets();
        // id -> item，分两阶段填
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (DashboardWidget w : widgets) {
            byId.put(w.getId(), baseItem(w));
        }
        if (widgets.isEmpty()) return new CachedDash(new ArrayList<>(), System.currentTimeMillis(), false);

        // 1) count 先算（pageSize=1，只取 total，很快）
        Map<String, ReportQueryEngine.QueryResult> light = new HashMap<>();
        for (DashboardWidget w : widgets) {
            Map<String, Object> item = byId.get(w.getId());
            String metric = nvl(w.getMetric()).toLowerCase();
            // count_distinct / sum 等需要明细行
            if (!"count".equals(metric)) continue;
            try {
                ReportDef report = configService.getReport(w.getReportCode());
                Map<String, Object> filters = buildFilters(report, w);
                String ck = resultCache.key(report.getCode(), filters);
                ReportQueryEngine.QueryResult result = light.get(ck);
                if (result == null) {
                    result = engine.query(report, ReportQueryEngine.QueryRequest.of(filters, 1, 1));
                    light.put(ck, result);
                }
                item.put("value", aggregate(w, result));
                item.put("ok", true);
            } catch (Exception e) {
                item.put("value", null);
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
        }
        // 先落一版缓存，首页 count 立刻有数
        publishPartial(byId, widgets);

        // 2) sum/avg/min/max/count_distinct 再算（全量，慢）
        Map<String, ReportQueryEngine.QueryResult> full = new HashMap<>();
        for (DashboardWidget w : widgets) {
            String metric2 = nvl(w.getMetric()).toLowerCase();
            if ("count".equals(metric2)) continue;
            Map<String, Object> item = byId.get(w.getId());
            try {
                ReportDef report = configService.getReport(w.getReportCode());
                Map<String, Object> filters = buildFilters(report, w);
                String ck = resultCache.key(report.getCode(), filters);
                ReportQueryEngine.QueryResult result = full.get(ck);
                if (result == null) {
                    var cached = resultCache.getFresh(ck);
                    if (cached != null) {
                        result = resultCache.toQueryResult(cached, 1, Integer.MAX_VALUE);
                    } else {
                        int size = Math.max(1000, engine.maxRootRows());
                        result = engine.query(report, ReportQueryEngine.QueryRequest.of(filters, 1, size));
                    }
                    full.put(ck, result);
                }
                item.put("value", aggregate(w, result));
                item.put("ok", true);
            } catch (Exception e) {
                item.put("value", null);
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            publishPartial(byId, widgets);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardWidget w : widgets) {
            Map<String, Object> item = new LinkedHashMap<>(byId.get(w.getId()));
            item.putIfAbsent("value", null);
            item.putIfAbsent("ok", true);
            out.add(item);
        }
        out.sort(Comparator.comparingInt(a -> a.get("sort") == null ? 0 : Integer.parseInt(String.valueOf(a.get("sort")))));
        return new CachedDash(out, System.currentTimeMillis(), false);
    }

    private void publishPartial(Map<String, Map<String, Object>> byId, List<DashboardWidget> widgets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardWidget w : widgets) {
            out.add(new LinkedHashMap<>(byId.get(w.getId())));
        }
        out.sort(Comparator.comparingInt(a -> a.get("sort") == null ? 0 : Integer.parseInt(String.valueOf(a.get("sort")))));
        // partial=true 表示还在算
        cache = new CachedDash(out, System.currentTimeMillis(), true);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 当天 / 本周（周一起）/ 当月；最长不超过一个月 */
    static Map<String, String> timeBounds(String timeRange) {
        LocalDate today = LocalDate.now();
        LocalDate start;
        LocalDate end = today;
        String r = timeRange == null || timeRange.isBlank() ? "month" : timeRange.toLowerCase();
        switch (r) {
            case "today" -> start = today;
            case "week" -> {
                start = today.with(DayOfWeek.MONDAY);
                end = today;
            }
            case "month" -> {
                start = today.withDayOfMonth(1);
                end = today;
            }
            default -> {
                start = today.withDayOfMonth(1);
            }
        }
        if (start.isAfter(end)) start = end;
        // 硬限制：不超过 31 天
        if (start.isBefore(end.minusDays(31))) start = end.minusDays(31);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("start", DAY.format(start));
        m.put("end", DAY.format(end));
        return m;
    }

    /** 合并时间窗到查询 filters；dateKey 为空时用报表首个 date_range 筛选名 */
    @SuppressWarnings("unchecked")
    Map<String, Object> buildFilters(ReportDef report, DashboardWidget w) {
        Map<String, Object> filters = new LinkedHashMap<>(readFilters(w.getFiltersJson()));
        String dateKey = w.getDateFilterKey();
        if (dateKey == null || dateKey.isBlank()) {
            dateKey = findDateFilterKey(report.getFiltersJson());
        }
        if (dateKey == null || dateKey.isBlank()) return filters;
        Map<String, String> bounds = timeBounds(w.getTimeRange());
        Object cur = filters.get(dateKey);
        Map<String, Object> range = new LinkedHashMap<>();
        if (cur instanceof Map<?, ?> m) {
            m.forEach((k, v) -> range.put(String.valueOf(k), v));
        }
        range.put("start", bounds.get("start"));
        range.put("end", bounds.get("end"));
        filters.put(dateKey, range);
        return filters;
    }

    @SuppressWarnings("unchecked")
    private String findDateFilterKey(String filtersJson) {
        try {
            List<Map<String, Object>> arr = mapper.readValue(
                    filtersJson == null || filtersJson.isBlank() ? "[]" : filtersJson, List.class);
            for (Map<String, Object> f : arr) {
                String op = String.valueOf(f.getOrDefault("op", f.getOrDefault("type", "")));
                String key = String.valueOf(f.getOrDefault("key", ""));
                if (("date_range".equals(op) || "date".equals(op)) && !key.isBlank() && !"null".equals(key)) {
                    return key;
                }
            }
            for (Map<String, Object> f : arr) {
                String key = String.valueOf(f.getOrDefault("key", ""));
                if (key.toLowerCase().contains("time") || key.toLowerCase().contains("date")) return key;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Object aggregate(DashboardWidget w, ReportQueryEngine.QueryResult result) {
        String metric = w.getMetric() == null ? "count" : w.getMetric().toLowerCase();
        if ("count".equals(metric)) {
            return result.total() > 0 ? result.total() : result.rows().size();
        }
        String field = w.getField();
        if (field == null || field.isBlank()) return null;
        // 去重计数：客户数 / 合同数 / 销售订单数等
        if ("count_distinct".equals(metric) || "distinct".equals(metric)) {
            Set<String> uniq = new LinkedHashSet<>();
            for (Map<String, Object> row : result.rows()) {
                Object v = row.get(field);
                if (v == null) continue;
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s) && !"-".equals(s)) uniq.add(s);
            }
            return uniq.size();
        }
        List<Double> nums = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            Object v = row.get(field);
            if (v == null) continue;
            try {
                nums.add(Double.parseDouble(String.valueOf(v).trim()));
            } catch (Exception ignored) {
            }
        }
        if (nums.isEmpty()) return 0;
        return switch (metric) {
            case "sum" -> round2(nums.stream().mapToDouble(Double::doubleValue).sum());
            case "avg" -> round2(nums.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            case "min" -> round2(nums.stream().mapToDouble(Double::doubleValue).min().orElse(0));
            case "max" -> round2(nums.stream().mapToDouble(Double::doubleValue).max().orElse(0));
            default -> nums.size();
        };
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFilters(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
