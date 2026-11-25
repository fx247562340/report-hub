package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.domain.*;
import com.reporthub.engine.ReportRelationRegistry;
import com.reporthub.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConfigService {
    private final DataSourceRepo dataSourceRepo;
    private final ApiEndpointRepo endpointRepo;
    private final DatasetRepo datasetRepo;
    private final FieldMappingRepo fieldMappingRepo;
    private final ReportRepo reportRepo;
    private final ObjectMapper mapper;
    private final com.reporthub.engine.SessionAuthService sessionAuthService;
    private final com.reporthub.engine.UpstreamClient upstream;
    private final com.reporthub.engine.U9cOAuthService u9cOAuth;
    private final ReportResultCache resultCache;

    public ConfigService(DataSourceRepo dataSourceRepo,
                         ApiEndpointRepo endpointRepo,
                         DatasetRepo datasetRepo,
                         FieldMappingRepo fieldMappingRepo,
                         ReportRepo reportRepo,
                         ObjectMapper mapper,
                         com.reporthub.engine.SessionAuthService sessionAuthService,
                         com.reporthub.engine.UpstreamClient upstream,
                         com.reporthub.engine.U9cOAuthService u9cOAuth,
                         ReportResultCache resultCache) {
        this.dataSourceRepo = dataSourceRepo;
        this.endpointRepo = endpointRepo;
        this.datasetRepo = datasetRepo;
        this.fieldMappingRepo = fieldMappingRepo;
        this.reportRepo = reportRepo;
        this.mapper = mapper;
        this.sessionAuthService = sessionAuthService;
        this.upstream = upstream;
        this.u9cOAuth = u9cOAuth;
        this.resultCache = resultCache;
    }

    public java.util.Map<String, Object> testLogin(String code) {
        DataSourceDef ds = dataSourceRepo.findById(code)
                .orElseThrow(() -> new BizException("数据源不存在: " + code));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (com.reporthub.engine.U9cOAuthService.isU9c(ds.getAuthType())) {
            String token = u9cOAuth.login(ds);
            out.put("ok", true);
            out.put("mode", "u9c_oauth");
            out.put("hasToken", token != null && !token.isEmpty());
            out.put("tokenPreview", token == null ? "" : token.substring(0, Math.min(18, token.length())) + "...");
            return out;
        }
        if (!"session".equalsIgnoreCase(ds.getAuthType())) {
            throw new BizException("仅「登录会话」或「U9C OAuth」认证支持测试登录");
        }
        var state = sessionAuthService.login(ds, null);
        out.put("ok", true);
        out.put("mode", "session");
        out.put("cookies", state.cookies.keySet());
        out.put("hasToken", state.token != null && !state.token.isEmpty());
        return out;
    }

    /**
     * Test-call an endpoint (saved or unsaved draft) and return raw + parsed preview.
     * body: { endpoint: {...}, dataSourceCode?, sampleParams?: {k:v} }
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> testEndpoint(java.util.Map<String, Object> body) {
        java.util.Map<String, Object> epMap = body.get("endpoint") instanceof java.util.Map<?, ?> m
                ? (java.util.Map<String, Object>) m : null;
        if (epMap == null && body.get("code") != null) {
            ApiEndpointDef saved = endpointRepo.findById(String.valueOf(body.get("code")))
                    .orElseThrow(() -> new BizException("接口不存在"));
            epMap = mapper.convertValue(saved, java.util.Map.class);
        }
        if (epMap == null) throw new BizException("缺少接口定义");

        ApiEndpointDef ep = mapper.convertValue(epMap, ApiEndpointDef.class);
        String configuredDs = ep.getDataSourceCode();
        if (body.get("dataSourceCode") != null) configuredDs = String.valueOf(body.get("dataSourceCode"));
        final String dsCode = configuredDs;
        DataSourceDef ds = dataSourceRepo.findById(dsCode)
                .orElseThrow(() -> new BizException("数据源不存在: " + dsCode));

        java.util.Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("page", 1);
        ctx.put("pageSize", 5);
        ctx.put("id", "1");
        ctx.put("ids", "1,2,3");
        ctx.put("key", "1");
        ctx.put("filter", new java.util.LinkedHashMap<String, Object>());
        java.util.Map<String, Object> filters = new java.util.LinkedHashMap<>();
        filters.put("done_date", new java.util.LinkedHashMap<String, Object>(java.util.Map.of("start", "2024-01-01", "end", "2024-12-31")));
        ctx.put("filters", filters);
        ctx.put("filter.done_date.start", "2024-01-01");
        ctx.put("filter.done_date.end", "2024-12-31");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sample = body.get("sampleParams") instanceof java.util.Map<?, ?> s
                ? (java.util.Map<String, Object>) s : java.util.Map.of();
        ctx.putAll(sample);

        long t0 = System.currentTimeMillis();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            var result = upstreamClient().fetchList(ds, ep, ctx);
            out.put("ok", true);
            out.put("url", result.url());
            out.put("durationMs", result.durationMs());
            out.put("rowCount", result.rawRows().size());
            out.put("total", result.total());
            out.put("rows", result.rawRows());
            out.put("raw", result.rawBody());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            out.put("durationMs", System.currentTimeMillis() - t0);
        }
        return out;
    }

    private com.reporthub.engine.UpstreamClient upstreamClient() {
        return upstream;
    }

    private void requireCode(String code, String label) {
        if (code == null || code.isBlank()) {
            throw new BizException(label + "编码不能为空");
        }
    }

    public static final String SECRET_MASK = "******";

    // ---- datasources ----
    public List<DataSourceDef> listDatasources() { return dataSourceRepo.findAll(); }

    public List<DataSourceDef> listDatasourcesMasked() {
        return dataSourceRepo.findAll().stream().map(this::maskDatasource).toList();
    }

    public DataSourceDef maskDatasource(DataSourceDef src) {
        if (src == null) return null;
        DataSourceDef copy = new DataSourceDef();
        copy.setCode(src.getCode());
        copy.setName(src.getName());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setAuthType(src.getAuthType());
        copy.setAuthConfig(maskAuthConfig(src.getAuthType(), src.getAuthConfig()));
        copy.setEnv(src.getEnv());
        copy.setTimeoutMs(src.getTimeoutMs());
        copy.setUpdatedAt(src.getUpdatedAt());
        return copy;
    }

    @Transactional
    public DataSourceDef saveDatasource(DataSourceDef def) {
        requireCode(def.getCode(), "数据源");
        String code = def.getCode().trim();
        def.setCode(code);
        DataSourceDef existing = dataSourceRepo.findById(code).orElse(null);
        if (existing != null) {
            def.setAuthConfig(mergeAuthSecrets(def.getAuthType(), def.getAuthConfig(),
                    existing.getAuthType(), existing.getAuthConfig()));
        }
        def.setUpdatedAt(Instant.now());
        DataSourceDef saved = dataSourceRepo.save(def);
        sessionAuthService.invalidate(code);
        u9cOAuth.invalidate(code);
        resultCache.invalidateAll();
        return saved;
    }

    @Transactional
    public void deleteDatasource(String code) {
        dataSourceRepo.deleteById(code);
        sessionAuthService.invalidate(code);
        u9cOAuth.invalidate(code);
        resultCache.invalidateAll();
    }

    @SuppressWarnings("unchecked")
    String maskAuthConfig(String authType, String json) {
        if (json == null || json.isBlank()) return json;
        try {
            Object parsed = mapper.readValue(json, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) return json;
            Map<String, Object> masked = maskMap(authType, (Map<String, Object>) raw);
            return mapper.writeValueAsString(masked);
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    String mergeAuthSecrets(String incomingAuthType, String incomingJson,
                            String existingAuthType, String existingJson) {
        if (existingJson == null || existingJson.isBlank()) return incomingJson;
        if (incomingJson == null || incomingJson.isBlank()) return existingJson;
        if (incomingAuthType != null && existingAuthType != null
                && !incomingAuthType.equalsIgnoreCase(existingAuthType)) {
            return incomingJson;
        }
        try {
            Object inObj = mapper.readValue(incomingJson, Object.class);
            Object exObj = mapper.readValue(existingJson, Object.class);
            if (!(inObj instanceof Map<?, ?> inMap) || !(exObj instanceof Map<?, ?> exMap)) {
                return incomingJson;
            }
            Map<String, Object> merged = mergeMap(incomingAuthType,
                    (Map<String, Object>) inMap, (Map<String, Object>) exMap);
            return mapper.writeValueAsString(merged);
        } catch (Exception e) {
            return incomingJson;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskMap(String authType, Map<String, Object> src) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) {
                out.put(k, maskMap(authType, (Map<String, Object>) sub));
            } else if (v instanceof String s && !s.isBlank() && isSensitiveKey(authType, k)) {
                out.put(k, SECRET_MASK);
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeMap(String authType, Map<String, Object> incoming, Map<String, Object> existing) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String k = e.getKey();
            Object inVal = e.getValue();
            Object exVal = existing.get(k);
            if (inVal instanceof Map<?, ?> inSub && exVal instanceof Map<?, ?> exSub) {
                out.put(k, mergeMap(authType, (Map<String, Object>) inSub, (Map<String, Object>) exSub));
            } else if (isSensitiveKey(authType, k)) {
                String inStr = inVal == null ? "" : String.valueOf(inVal).trim();
                boolean keepOld = inStr.isEmpty() || SECRET_MASK.equals(inStr);
                if (keepOld && exVal instanceof String exStr && !exStr.isBlank()) {
                    out.put(k, exStr);
                } else {
                    out.put(k, inVal);
                }
            } else {
                out.put(k, inVal);
            }
        }
        return out;
    }

    private static boolean isSensitiveKey(String authType, String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.equals("password") || k.equals("clientsecret") || k.equals("client_secret")
                || k.equals("secret") || k.equals("apikey") || k.equals("api_key")
                || k.equals("salt") || k.equals("authorization") || k.equals("token")
                || k.equals("access_token") || k.equals("accesstoken")) {
            return true;
        }
        return "api_key".equalsIgnoreCase(authType) && k.equals("value");
    }

    // ---- endpoints ----
    public List<ApiEndpointDef> listEndpoints() { return endpointRepo.findAll(); }

    @Transactional
    public ApiEndpointDef saveEndpoint(ApiEndpointDef def) {
        requireCode(def.getCode(), "接口");
        def.setCode(def.getCode().trim());
        def.setUpdatedAt(Instant.now());
        ApiEndpointDef saved = endpointRepo.save(def);
        resultCache.invalidateAll();
        return saved;
    }

    @Transactional
    public void deleteEndpoint(String code) {
        endpointRepo.deleteById(code);
        resultCache.invalidateAll();
    }

    // ---- datasets + mappings ----
    public List<DatasetDef> listDatasets() { return datasetRepo.findAll(); }

    public List<FieldMappingDef> listMappings(String datasetCode) {
        return fieldMappingRepo.findByDatasetCodeOrderBySortAsc(datasetCode);
    }

    @Transactional
    public DatasetDef saveDataset(DatasetDef def, List<FieldMappingDef> mappings) {
        requireCode(def.getCode(), "数据集");
        def.setCode(def.getCode().trim());
        def.setUpdatedAt(Instant.now());
        datasetRepo.save(def);
        if (mappings != null) {
            fieldMappingRepo.deleteByDatasetCode(def.getCode());
            fieldMappingRepo.flush();
            int i = 0;
            for (FieldMappingDef m : mappings) {
                m.setId(null);
                m.setDatasetCode(def.getCode());
                if (m.getSort() == null) m.setSort(i++);
                fieldMappingRepo.save(m);
            }
        }
        resultCache.invalidateAll();
        return def;
    }

    @Transactional
    public void deleteDataset(String code) {
        fieldMappingRepo.deleteByDatasetCode(code);
        datasetRepo.deleteById(code);
        resultCache.invalidateAll();
    }

    // ---- relations & lineage (read-only, code-managed) ----
    public List<RelationDef> listRelations(String reportCode) {
        return ReportRelationRegistry.relationsFor(reportCode);
    }

    public Map<String, Object> reportLineage(String reportCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportCode", reportCode);
        out.put("relations", ReportRelationRegistry.relationsFor(reportCode));
        out.put("builtin", ReportRelationRegistry.builtinLineageFor(reportCode));
        return out;
    }

    // ---- reports ----
    public List<ReportDef> listReports() { return reportRepo.findAllByOrderByUpdatedAtDesc(); }

    public ReportDef getReport(String code) {
        return reportRepo.findById(code).orElseThrow(() -> new BizException("报表不存在: " + code));
    }

    @Transactional
    public ReportDef saveReport(ReportDef def) {
        requireCode(def.getCode(), "报表");
        def.setCode(def.getCode().trim());
        validateJsonArray(def.getFieldsJson(), "fieldsJson");
        validateJsonArray(def.getFiltersJson(), "filtersJson");
        validateJsonArray(def.getOrderByJson(), "orderByJson");
        validateJsonArray(def.getCalcsJson(), "calcsJson");
        validateJsonArray(def.getPaginationJson() == null ? "[]" : def.getPaginationJson().trim().startsWith("[")
                ? def.getPaginationJson() : "[]", "paginationJson");
        def.setUpdatedAt(Instant.now());
        ReportDef saved = reportRepo.save(def);
        resultCache.invalidateReport(saved.getCode());
        return saved;
    }

    @Transactional
    public void deleteReport(String code) {
        reportRepo.deleteById(code);
        resultCache.invalidateReport(code);
    }

    private void validateJsonArray(String json, String name) {
        if (json == null || json.isBlank()) return;
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(name + " 不是合法 JSON");
        }
    }

    public Map<String, Object> reportMeta(String code) {
        ReportDef report = getReport(code);
        return Map.of(
                "code", report.getCode(),
                "name", report.getName(),
                "description", report.getDescription() == null ? "" : report.getDescription(),
                "rootDataset", report.getRootDataset(),
                "fields", report.getFieldsJson(),
                "filters", report.getFiltersJson() == null ? "[]" : report.getFiltersJson(),
                "orderBy", report.getOrderByJson() == null ? "[]" : report.getOrderByJson(),
                "calcs", report.getCalcsJson() == null ? "[]" : report.getCalcsJson(),
                "pagination", report.getPaginationJson() == null ? "{}" : report.getPaginationJson(),
                "relations", listRelations(code)
        );
    }
}
