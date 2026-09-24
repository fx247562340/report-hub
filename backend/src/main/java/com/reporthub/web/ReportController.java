package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.engine.ReportQueryEngine;
import com.reporthub.service.ConfigService;
import com.reporthub.service.ReportResultCache;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ConfigService configService;
    private final ReportQueryEngine engine;
    private final ReportResultCache resultCache;

    public ReportController(ConfigService configService,
                            ReportQueryEngine engine,
                            ReportResultCache resultCache) {
        this.configService = configService;
        this.engine = engine;
        this.resultCache = resultCache;
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

        String cacheKey = resultCache.key(report.getCode(), filters);
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

    /** Export all filtered rows as xlsx (not just current page). */
    @PostMapping("/{code}/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = body == null ? Map.of() :
                (Map<String, Object>) body.getOrDefault("filters", Map.of());
        var report = configService.getReport(code);
        String cacheKey = resultCache.key(report.getCode(), filters);
        var full = resultCache.getOrLoad(cacheKey, () -> loadFull(report.getCode(), filters));
        byte[] bytes = com.reporthub.service.ExcelExportService.toXlsx(
                full.columns(), full.rows(), report.getName());
        String filename = java.net.URLEncoder.encode(report.getName() + ".xlsx", java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
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
