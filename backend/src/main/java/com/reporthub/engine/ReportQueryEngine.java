package com.reporthub.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.config.ReportHubProperties;
import com.reporthub.domain.*;
import com.reporthub.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class ReportQueryEngine {
    private final ObjectMapper mapper;
    private final UpstreamClient upstream;
    private final DataSourceRepo dataSourceRepo;
    private final ApiEndpointRepo endpointRepo;
    private final DatasetRepo datasetRepo;
    private final FieldMappingRepo fieldMappingRepo;
    private final RelationRepo relationRepo;
    private final ReportHubProperties props;

    public ReportQueryEngine(ObjectMapper mapper,
                             UpstreamClient upstream,
                             DataSourceRepo dataSourceRepo,
                             ApiEndpointRepo endpointRepo,
                             DatasetRepo datasetRepo,
                             FieldMappingRepo fieldMappingRepo,
                             RelationRepo relationRepo,
                             ReportHubProperties props) {
        this.mapper = mapper;
        this.upstream = upstream;
        this.dataSourceRepo = dataSourceRepo;
        this.endpointRepo = endpointRepo;
        this.datasetRepo = datasetRepo;
        this.fieldMappingRepo = fieldMappingRepo;
        this.relationRepo = relationRepo;
        this.props = props;
    }

    public record QueryRequest(Map<String, Object> filters, int page, int pageSize) {
        public static QueryRequest of(Map<String, Object> filters, int page, int pageSize) {
            return new QueryRequest(filters, page <= 0 ? 1 : page, pageSize <= 0 ? 50 : pageSize);
        }
    }

    public int maxRootRows() {
        return props.getEngine().getMaxRootRows();
    }

    public record TraceCall(String mode, String endpoint, String url, int rows, long ms) {}

    public record QueryResult(List<Map<String, Object>> columns,
                              List<Map<String, Object>> rows,
                              int page, int pageSize, long total,
                              List<TraceCall> trace, long durationMs) {}

    public record JoinPair(String leftField, String rightField) {}

    public QueryResult query(ReportDef report, QueryRequest req) {
        long t0 = System.currentTimeMillis();
        List<TraceCall> trace = new ArrayList<>();
        List<FieldRuleBundle> bundles = loadRules();

        RootFetch rootFetch = fetchRoot(report, req, trace, bundles);
        Dataset root = rootFetch.dataset();
        List<RelationDef> relations = relationRepo.findByReportCodeOrderBySortAsc(report.getCode());

        Dataset current = root;
        for (RelationDef rel : relations) {
            Dataset right = fetchRelated(rel, current, req, trace, bundles);
            current = join(current, right, rel);
        }

        List<Map<String, Object>> projected = project(current, report);
        applyCalcs(projected, report);
        List<Map<String, Object>> all = applyInMemoryFilters(projected, req.filters());
        sortRows(all, report);

        // 分页总条数优先取主表上游 count（totalPath），避免写死/只按本页行数
        long total = rootFetch.upstreamTotal() != null ? rootFetch.upstreamTotal() : all.size();
        List<Map<String, Object>> pageRows = all;

        return new QueryResult(parseResultColumns(report), pageRows, req.page(), req.pageSize(), total,
                trace, System.currentTimeMillis() - t0);
    }

    private record RootFetch(Dataset dataset, Long upstreamTotal) {}

    private record FieldRuleBundle(String datasetCode, List<JsonPaths.FieldRule> rules) {}

    private List<FieldRuleBundle> loadRules() {
        return datasetRepo.findAll().stream()
                .map(ds -> new FieldRuleBundle(
                        ds.getCode(),
                        toRules(fieldMappingRepo.findByDatasetCodeOrderBySortAsc(ds.getCode()))))
                .toList();
    }

    private List<JsonPaths.FieldRule> toRules(List<FieldMappingDef> defs) {
        return defs.stream()
                .map(d -> new JsonPaths.FieldRule(d.getSourcePath(), d.getTargetField(), d.getDataType(), d.getTransform()))
                .toList();
    }

    private List<JsonPaths.FieldRule> rulesFor(List<FieldRuleBundle> all, String datasetCode) {
        return all.stream()
                .filter(b -> b.datasetCode().equals(datasetCode))
                .map(FieldRuleBundle::rules)
                .findFirst()
                .orElse(List.of());
    }

    /** Map upstream rows into dataset rows with keys prefixed as datasetCode.field (and bare field). */
    private Dataset materialize(String datasetCode, List<Map<String, Object>> mapped) {
        Dataset out = new Dataset(datasetCode);
        for (Map<String, Object> row : mapped) {
            Map<String, Object> labeled = new LinkedHashMap<>();
            row.forEach((k, v) -> {
                labeled.put(k, v);
                labeled.put(datasetCode + "." + k, v);
            });
            out.addRow(labeled);
        }
        return out;
    }

    private RootFetch fetchRoot(ReportDef report, QueryRequest req, List<TraceCall> trace, List<FieldRuleBundle> bundles) {
        DatasetDef ds = datasetRepo.findById(report.getRootDataset())
                .orElseThrow(() -> new BizException("root dataset not found: " + report.getRootDataset()));
        ApiEndpointDef ep = endpointRepo.findById(ds.getEndpointCode())
                .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
        DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode())
                .orElseThrow(() -> new BizException("datasource not found: " + ep.getDataSourceCode()));

        Map<String, Object> ctx = baseCtx(req);
        applyPaginationParams(ctx, report, req);
        ApiEndpointDef epUse = withTotalOverride(ep, report);
        var result = upstream.fetchList(src, epUse, ctx);
        if (result.rawRows().size() > props.getEngine().getMaxRootRows()) {
            throw new BizException("root rows exceed limit " + props.getEngine().getMaxRootRows() + ", please narrow filters");
        }
        trace.add(new TraceCall("root", ep.getCode(), result.url(), result.rawRows().size(), result.durationMs()));
        Dataset data = materialize(report.getRootDataset(), upstream.mapAll(result.rawRows(), rulesFor(bundles, report.getRootDataset())));
        Long upstreamTotal = result.total() == null ? null : result.total().longValue();
        return new RootFetch(data, upstreamTotal);
    }

    private Dataset fetchRelated(RelationDef rel, Dataset left, QueryRequest req,
                                 List<TraceCall> trace, List<FieldRuleBundle> bundles) {
        String mode = rel.getFetchMode() == null ? "child_batch" : rel.getFetchMode();
        Map<String, Object> cfg = readJsonMap(rel.getFetchConfig());
        List<JsonPaths.FieldRule> ruleset = rulesFor(bundles, rel.getRightDataset());
        return switch (mode) {
            case "dual_list" -> fetchDualList(rel, req, trace, cfg, ruleset);
            case "child_lookup" -> fetchByKeys(rel, left, trace, cfg, ruleset, true);
            case "child_batch" -> fetchByKeys(rel, left, trace, cfg, ruleset, false);
            default -> throw new BizException("unknown fetchMode: " + mode);
        };
    }

    private Dataset fetchDualList(RelationDef rel, QueryRequest req, List<TraceCall> trace,
                                  Map<String, Object> cfg, List<JsonPaths.FieldRule> ruleset) {
        String configuredEp = str(cfg, "rightEndpoint", null);
        final String epCode;
        if (configuredEp == null) {
            DatasetDef ds = datasetRepo.findById(rel.getRightDataset())
                    .orElseThrow(() -> new BizException("dataset not found: " + rel.getRightDataset()));
            epCode = ds.getEndpointCode();
        } else {
            epCode = configuredEp;
        }
        ApiEndpointDef ep = endpointRepo.findById(epCode)
                .orElseThrow(() -> new BizException("endpoint not found: " + epCode));
        DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();

        Map<String, Object> ctx = baseCtx(req);
        var result = upstream.fetchList(src, ep, ctx);
        trace.add(new TraceCall("dual_list", ep.getCode(), result.url(), result.rawRows().size(), result.durationMs()));
        return materialize(rel.getRightDataset(), upstream.mapAll(result.rawRows(), ruleset));
    }

    private Dataset fetchByKeys(RelationDef rel, Dataset left, List<TraceCall> trace,
                                Map<String, Object> cfg, List<JsonPaths.FieldRule> ruleset, boolean lookup) {
        List<JoinPair> pairs = parseOn(rel.getOnFields());
        if (pairs.isEmpty()) throw new BizException("relation onFields empty: " + rel.getCode());

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : left.rows()) {
            String k = JsonPaths.normKey(resolve(row, rel.getLeftDataset(), pairs.get(0).leftField()));
            if (!k.isEmpty()) keys.add(k);
        }

        DatasetDef ds = datasetRepo.findById(rel.getRightDataset())
                .orElseThrow(() -> new BizException("dataset not found: " + rel.getRightDataset()));
        String listEp = str(cfg, "endpoint", ds.getEndpointCode());
        String detailEp = str(cfg, "detailEndpoint", listEp);

        if (keys.isEmpty()) {
            return materialize(rel.getRightDataset(), List.of());
        }

        if (lookup) {
            ApiEndpointDef ep = endpointRepo.findById(detailEp)
                    .orElseThrow(() -> new BizException("endpoint not found: " + detailEp));
            DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();
            String pathParam = str(cfg, "pathParam", "id");
            String keyParam = str(cfg, "keyParam", pathParam);
            int concurrency = intCfg(cfg, "concurrency", props.getEngine().getLookupConcurrency());
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
            long t0 = System.currentTimeMillis();
            try {
                List<Callable<List<Map<String, Object>>>> tasks = new ArrayList<>();
                for (String key : keys) {
                    tasks.add(() -> {
                        Map<String, Object> ctx = new HashMap<>();
                        ctx.put(keyParam, key);
                        ctx.put(pathParam, key);
                        ctx.put("id", key);
                        ctx.put("key", key);
                        var result = upstream.fetchList(src, ep, ctx);
                        return upstream.mapAll(result.rawRows(), ruleset);
                    });
                }
                List<Map<String, Object>> all = new ArrayList<>();
                for (Future<List<Map<String, Object>>> f : pool.invokeAll(tasks)) {
                    all.addAll(f.get());
                }
                trace.add(new TraceCall("child_lookup", ep.getCode(), "(per-key x" + keys.size() + ")",
                        all.size(), System.currentTimeMillis() - t0));
                return materialize(rel.getRightDataset(), all);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new BizException("lookup fetch failed: " + cause.getMessage(), HttpStatus.BAD_GATEWAY);
            } finally {
                pool.shutdownNow();
            }
        }

        ApiEndpointDef ep = endpointRepo.findById(listEp)
                .orElseThrow(() -> new BizException("endpoint not found: " + listEp));
        DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();
        String keyParam = str(cfg, "keyParam", "ids");
        int maxPer = intCfg(cfg, "maxKeysPerCall", props.getEngine().getMaxKeysPerBatch());
        List<String> keyList = new ArrayList<>(keys);
        List<Map<String, Object>> all = new ArrayList<>();
        int rows = 0;
        long ms = 0;
        String lastUrl = "";
        for (int i = 0; i < keyList.size(); i += maxPer) {
            List<String> chunk = keyList.subList(i, Math.min(keyList.size(), i + maxPer));
            Map<String, Object> ctx = new HashMap<>();
            String joined = String.join(",", chunk);
            ctx.put(keyParam, joined);
            ctx.put("ids", joined);
            ctx.put("keyList", new ArrayList<>(chunk));
            if (!chunk.isEmpty()) {
                ctx.put("id", chunk.get(0));
                ctx.put("key", chunk.get(0));
            }
            var result = upstream.fetchList(src, ep, ctx);
            rows += result.rawRows().size();
            ms += result.durationMs();
            lastUrl = result.url();
            all.addAll(upstream.mapAll(result.rawRows(), ruleset));
        }
        trace.add(new TraceCall("child_batch", ep.getCode(), lastUrl, rows, ms));
        return materialize(rel.getRightDataset(), all);
    }

    private Object resolve(Map<String, Object> row, String datasetCode, String field) {
        Object v = row.get(datasetCode + "." + field);
        if (v != null) return v;
        v = row.get(field);
        return v;
    }

    public List<JoinPair> parseOn(String onFieldsJson) {
        try {
            List<JoinPair> list = mapper.readValue(onFieldsJson, new TypeReference<List<JoinPair>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            try {
                List<Map<String, String>> raw = mapper.readValue(onFieldsJson, new TypeReference<>() {});
                return raw.stream().map(m -> new JoinPair(m.get("leftField"), m.get("rightField"))).toList();
            } catch (Exception e2) {
                throw new BizException("invalid onFields json: " + onFieldsJson);
            }
        }
    }

    private Dataset join(Dataset left, Dataset right, RelationDef rel) {
        List<JoinPair> pairs = parseOn(rel.getOnFields());
        boolean toMany = "1-N".equals(rel.getCardinality()) || "N-N".equals(rel.getCardinality());
        boolean nested = "nested".equals(rel.getExpandMode());
        String leftDs = rel.getLeftDataset();
        String rightDs = rel.getRightDataset();

        Map<String, List<Map<String, Object>>> index = new HashMap<>();
        for (Map<String, Object> r : right.rows()) {
            String k = pairs.stream()
                    .map(p -> JsonPaths.normKey(resolve(r, rightDs, p.rightField())))
                    .collect(Collectors.joining("||"));
            index.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }

        Dataset out = new Dataset(leftDs);
        for (Map<String, Object> l : left.rows()) {
            String k = pairs.stream()
                    .map(p -> JsonPaths.normKey(resolve(l, leftDs, p.leftField())))
                    .collect(Collectors.joining("||"));
            List<Map<String, Object>> matches = index.getOrDefault(k, List.of());
            if (matches.isEmpty()) {
                if ("inner".equalsIgnoreCase(rel.getJoinType())) continue;
                out.addRow(new LinkedHashMap<>(l));
                continue;
            }
            if (!toMany) {
                Map<String, Object> row = new LinkedHashMap<>(l);
                mergeRight(row, matches.get(0), rightDs);
                out.addRow(row);
            } else if (nested) {
                Map<String, Object> row = new LinkedHashMap<>(l);
                List<Map<String, Object>> children = new ArrayList<>();
                for (Map<String, Object> c : matches) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    mergeRight(child, c, rightDs);
                    children.add(child);
                }
                row.put(rightDs + ".__list", children);
                out.addRow(row);
            } else {
                for (Map<String, Object> c : matches) {
                    Map<String, Object> row = new LinkedHashMap<>(l);
                    mergeRight(row, c, rightDs);
                    out.addRow(row);
                }
            }
        }
        return out;
    }

    private void mergeRight(Map<String, Object> row, Map<String, Object> rightRow, String rightDs) {
        rightRow.forEach((k, v) -> {
            if (k.startsWith(rightDs + ".")) {
                row.put(k, v);
            } else {
                row.put(rightDs + "." + k, v);
            }
        });
    }

    private List<Map<String, Object>> project(Dataset set, ReportDef report) {
        List<Map<String, Object>> cols = parseColumns(report);
        List<Map<String, Object>> out = new ArrayList<>();
        if (set == null) return out;
        for (Map<String, Object> row : set.rows()) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map<String, Object> col : cols) {
                String key = String.valueOf(col.get("key"));
                String from = col.get("from") == null ? key : String.valueOf(col.get("from"));
                Object v = row.get(from);
                if (v == null) v = row.get(key);
                r.put(key, v);
            }
            out.add(r);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyCalcs(List<Map<String, Object>> rows, ReportDef report) {
        List<CalcEvaluator.CalcField> calcs = parseCalcs(report);
        if (calcs.isEmpty()) return;
        for (Map<String, Object> row : rows) {
            for (CalcEvaluator.CalcField calc : calcs) {
                try {
                    row.put(calc.key(), CalcEvaluator.evaluate(calc, row));
                } catch (Exception e) {
                    row.put(calc.key(), null);
                }
            }
        }
    }

    private List<CalcEvaluator.CalcField> parseCalcs(ReportDef report) {
        String json = report.getCalcsJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = mapper.readValue(json, new TypeReference<>() {});
            List<CalcEvaluator.CalcField> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                String kind = String.valueOf(m.getOrDefault("kind", "formula"));
                out.add(new CalcEvaluator.CalcField(
                        String.valueOf(m.get("key")),
                        String.valueOf(m.getOrDefault("label", m.get("key"))),
                        kind,
                        m.get("formula") == null ? null : String.valueOf(m.get("formula")),
                        m.get("when") == null ? null : String.valueOf(m.get("when")),
                        m.get("then") == null ? "是" : String.valueOf(m.get("then")),
                        m.get("else") == null ? "否" : String.valueOf(m.get("else")),
                        m.get("precision") == null ? null : Integer.valueOf(String.valueOf(m.get("precision")))
                ));
            }
            return out;
        } catch (Exception e) {
            throw new BizException("invalid calcsJson");
        }
    }

    private List<Map<String, Object>> parseColumns(ReportDef report) {
        try {
            return mapper.readValue(report.getFieldsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new BizException("invalid report fieldsJson");
        }
    }

    private List<Map<String, Object>> parseResultColumns(ReportDef report) {
        List<Map<String, Object>> cols = new ArrayList<>(parseColumns(report));
        Set<String> existing = new HashSet<>();
        for (Map<String, Object> c : cols) {
            if (c.get("key") != null) existing.add(String.valueOf(c.get("key")));
        }
        for (CalcEvaluator.CalcField calc : parseCalcs(report)) {
            if (existing.contains(calc.key())) {
                // keep display order from fieldsJson; mark calc type
                for (Map<String, Object> c : cols) {
                    if (calc.key().equals(String.valueOf(c.get("key")))) {
                        c.putIfAbsent("calc", calc.kind());
                    }
                }
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", calc.key());
            c.put("label", calc.label());
            c.put("calc", calc.kind());
            cols.add(c);
        }
        return cols;
    }

    private List<Map<String, Object>> applyInMemoryFilters(List<Map<String, Object>> rows, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean ok = true;
            for (Map.Entry<String, Object> f : filters.entrySet()) {
                if (f.getValue() == null || f.getValue() instanceof Map) continue;
                String fv = String.valueOf(f.getValue());
                if (fv.isBlank()) continue;
                // only filter when this key exists on the projected row (upstream-only binds are ignored)
                if (!row.containsKey(f.getKey())) continue;
                Object cell = row.get(f.getKey());
                if (cell == null || !String.valueOf(cell).contains(fv)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(row);
        }
        return out;
    }

    private void sortRows(List<Map<String, Object>> rows, ReportDef report) {
        try {
            List<Map<String, String>> orderBy = mapper.readValue(
                    report.getOrderByJson() == null ? "[]" : report.getOrderByJson(),
                    new TypeReference<>() {});
            if (orderBy == null || orderBy.isEmpty()) return;
            String field = orderBy.get(0).get("field");
            boolean desc = "desc".equalsIgnoreCase(orderBy.get(0).get("dir"));
            Comparator<Map<String, Object>> cmp = Comparator.comparing(
                    r -> r.get(field) == null ? "" : String.valueOf(r.get(field)));
            rows.sort(desc ? cmp.reversed() : cmp);
        } catch (Exception ignored) {
        }
    }

    private List<Map<String, Object>> paginate(List<Map<String, Object>> rows, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = pageSize <= 0 ? 50 : pageSize;
        if (size == Integer.MAX_VALUE) return new ArrayList<>(rows);
        int from = (p - 1) * size;
        if (from >= rows.size()) return new ArrayList<>();
        return new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + size)));
    }

    /** Inject page/pageSize under configured request parameter names. */
    @SuppressWarnings("unchecked")
    private void applyPaginationParams(Map<String, Object> ctx, ReportDef report, QueryRequest req) {
        String json = report.getPaginationJson();
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> pg = mapper.readValue(json, Map.class);
            String pageParam = String.valueOf(pg.getOrDefault("pageParam", "page"));
            String pageSizeParam = String.valueOf(pg.getOrDefault("pageSizeParam", "pageSize"));
            int pageBase = pg.get("pageBase") == null ? 1 : Integer.parseInt(String.valueOf(pg.get("pageBase")));
            int page = req.page() <= 0 ? 1 : req.page();
            if (pageBase == 0) page = Math.max(0, page - 1);
            ctx.put(pageParam, page);
            ctx.put(pageSizeParam, req.pageSize());
            ctx.put("page", page);
            ctx.put("pageSize", req.pageSize());
        } catch (Exception ignored) {
        }
    }

    /** Prefer pagination.totalPath for root total count when configured. */
    private ApiEndpointDef withTotalOverride(ApiEndpointDef ep, ReportDef report) {
        String json = report.getPaginationJson();
        if (json == null || json.isBlank()) return ep;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pg = mapper.readValue(json, Map.class);
            Object totalPath = pg.get("totalPath");
            if (totalPath == null || String.valueOf(totalPath).isBlank()) return ep;
            ApiEndpointDef copy = new ApiEndpointDef();
            copy.setCode(ep.getCode());
            copy.setDataSourceCode(ep.getDataSourceCode());
            copy.setName(ep.getName());
            copy.setMethod(ep.getMethod());
            copy.setPath(ep.getPath());
            copy.setQueryTemplate(ep.getQueryTemplate());
            copy.setBodyTemplate(ep.getBodyTemplate());
            copy.setBodyType(ep.getBodyType());
            copy.setListPath(ep.getListPath());
            copy.setTotalPath(String.valueOf(totalPath));
            copy.setPagination(ep.getPagination());
            copy.setHeadersTemplate(ep.getHeadersTemplate());
            copy.setUpdatedAt(ep.getUpdatedAt());
            return copy;
        } catch (Exception e) {
            return ep;
        }
    }

    private Map<String, Object> baseCtx(QueryRequest req) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("page", req.page());
        ctx.put("pageSize", req.pageSize());
        Map<String, Object> filters = new LinkedHashMap<>();
        if (req.filters() != null) {
            req.filters().forEach((k, v) -> {
                if (v instanceof Map<?, ?> m) {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    m.forEach((nk, nv) -> nested.put(String.valueOf(nk), nv));
                    filters.put(k, nested);
                    ctx.put("filter." + k + ".start", nested.get("start"));
                    ctx.put("filter." + k + ".end", nested.get("end"));
                    ctx.put(k + ".start", nested.get("start"));
                    ctx.put(k + ".end", nested.get("end"));
                } else {
                    filters.put(k, v);
                    ctx.put(k, v);
                    ctx.put("filter." + k, v);
                }
            });
        }
        ctx.put("filter", filters);
        ctx.put("filters", filters);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int intCfg(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
