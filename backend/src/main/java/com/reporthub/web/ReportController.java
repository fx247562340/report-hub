package com.reporthub.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.ApiResponse;
import com.reporthub.domain.ReportDef;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.service.ConfigService;
import com.reporthub.service.QueryModeService;
import com.reporthub.service.ReportResultCache;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ConfigService configService;
    private final ReportQueryEngine engine;
    private final ReportResultCache resultCache;
    private final QueryModeService queryModeService;
    private final ObjectMapper mapper;

    public ReportController(ConfigService configService,
                            ReportQueryEngine engine,
                            ReportResultCache resultCache,
                            QueryModeService queryModeService,
                            ObjectMapper mapper) {
        this.configService = configService;
        this.engine = engine;
        this.resultCache = resultCache;
        this.queryModeService = queryModeService;
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.ok(configService.listReports());
    }

    @GetMapping("/{code}/meta")
    public ApiResponse<Map<String, Object>> meta(@PathVariable String code) {
        return ApiResponse.ok(configService.reportMeta(code));
    }

    @PostMapping("/{code}/query")
    public ApiResponse<ReportQueryEngine.QueryResult> query(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body) {
        var report = configService.getReport(code);
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = body == null ? Map.of() :
                (Map<String, Object>) body.getOrDefault("filters", Map.of());
        int page = body == null ? 1 : toInt(body.get("page"), 1);
        int pageSize = body == null ? 50 : toInt(body.get("pageSize"), 50);

        String cacheKey = resultCache.key(report.getCode(), filters, queryModeService.mode());
        var hit = resultCache.getFresh(cacheKey);
        if (hit != null) {
            return ApiResponse.ok(resultCache.toQueryResult(hit, page, pageSize));
        }

        // 先按页快速返回；同时预热全量缓存，紧接着导出可秒开
        resultCache.warmAsync(cacheKey, () -> loadFull(report.getCode(), filters));
        return ApiResponse.ok(engine.query(report, ReportQueryEngine.QueryRequest.of(filters, page, pageSize)));
    }

    @PostMapping("/{code}/debug")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ReportQueryEngine.QueryResult> debug(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body) {
        return query(code, body);
    }

    /** 弹窗明细：按主表一行拉取各关联子列表 */
    @PostMapping("/{code}/children")
    public ApiResponse<Map<String, Object>> children(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body) {
        var report = configService.getReport(code);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = body == null ? Map.of() :
                (Map<String, Object>) body.getOrDefault("row", Map.of());
        return ApiResponse.ok(engine.children(report, row));
    }

    /** Export all filtered rows as xlsx (not just current page). */
    @PostMapping("/{code}/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = body == null ? Map.of() :
                (Map<String, Object>) body.getOrDefault("filters", Map.of());
        var report = configService.getReport(code);
        String cacheKey = resultCache.key(report.getCode(), filters, queryModeService.mode());
        var full = resultCache.getOrLoad(cacheKey, () -> loadFull(report.getCode(), filters));
        byte[] bytes = com.reporthub.service.ExcelExportService.toXlsx(
                full.columns(), full.rows(), report.getName());
        String filename = java.net.URLEncoder.encode(
                report.getName() + monthSuffix(report, filters) + ".xlsx", java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    /** 导出文件名月份后缀：取报表 month 筛选的 yyyy-mm，如 -2026-11 */
    private String monthSuffix(ReportDef report, Map<String, Object> filters) {
        try {
            List<Map<String, Object>> defs = mapper.readValue(
                    report.getFiltersJson() == null ? "[]" : report.getFiltersJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> d : defs) {
                String op = String.valueOf(d.getOrDefault("op", d.getOrDefault("type", "")));
                if (!"month".equals(op)) continue;
                Object v = filters.get(String.valueOf(d.get("key")));
                String s = v instanceof Map<?, ?> m && m.get("start") != null
                        ? String.valueOf(m.get("start")) : String.valueOf(v == null ? "" : v);
                if (s.length() >= 7 && s.substring(0, 7).matches("\\d{4}-\\d{2}")) {
                    return "-" + s.substring(0, 7);
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private ReportResultCache.CachedResult loadFull(String code, Map<String, Object> filters) {
        var report = configService.getReport(code);
        int exportPageSize = Math.max(1000, engine.maxRootRows());
        var result = engine.query(report, ReportQueryEngine.QueryRequest.of(filters, 1, exportPageSize));
        return new ReportResultCache.CachedResult(
                result.columns(), result.rows(), result.total(), System.currentTimeMillis());
    }

    private int toInt(Object v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
