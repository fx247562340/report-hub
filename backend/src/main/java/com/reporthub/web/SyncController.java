package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.service.QueryModeService;
import com.reporthub.service.ReportResultCache;
import com.reporthub.service.SyncService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SyncController {
    private final SyncService syncService;
    private final QueryModeService modeService;
    private final ReportResultCache resultCache;

    public SyncController(SyncService syncService, QueryModeService modeService,
                          ReportResultCache resultCache) {
        this.syncService = syncService;
        this.modeService = modeService;
        this.resultCache = resultCache;
    }

    @GetMapping("/sync/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> status() {
        return ApiResponse.ok(syncService.listStatus());
    }

    @GetMapping("/sync/{datasetCode}/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> getConfig(@PathVariable String datasetCode) {
        return ApiResponse.ok(syncService.getStatus(datasetCode));
    }

    @PostMapping("/sync/{datasetCode}/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> saveConfig(@PathVariable String datasetCode,
                                                       @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(syncService.updateConfig(datasetCode, body));
    }

    @GetMapping("/sync/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> jobs(
            @RequestParam(required = false) String datasetCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String trigger,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(syncService.listJobs(datasetCode, status, trigger, limit));
    }

    @PostMapping("/sync/{datasetCode}/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> updateSchedule(@PathVariable String datasetCode, @RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("scheduleType", "manual"));
        String time = body.get("scheduleTime") == null ? null : String.valueOf(body.get("scheduleTime"));
        Integer weekday = body.get("scheduleWeekday") == null ? null : Integer.valueOf(String.valueOf(body.get("scheduleWeekday")));
        Integer interval = body.get("scheduleIntervalMin") == null ? null : Integer.valueOf(String.valueOf(body.get("scheduleIntervalMin")));
        return ApiResponse.ok(syncService.updateSchedule(datasetCode, type, time, weekday, interval));
    }

    @PostMapping("/sync/{datasetCode}/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> stop(@PathVariable String datasetCode) {
        syncService.requestCancel(datasetCode);
        return ApiResponse.ok(Map.of("cancelled", datasetCode));
    }

    @PostMapping("/sync/{datasetCode}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> setEnabled(@PathVariable String datasetCode, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        syncService.setEnabled(datasetCode, enabled);
        return ApiResponse.ok(Map.of("datasetCode", datasetCode, "enabled", enabled));
    }

    @PostMapping("/sync/run-all")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> runAll(@RequestBody(required = false) java.util.Map<String, Object> body) {
        String strategy = String.valueOf(body == null ? "incremental" : body.getOrDefault("strategy", "incremental"));
        return ApiResponse.ok(syncService.triggerAll(strategy));
    }

    @PostMapping("/sync/{datasetCode}/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> run(@PathVariable String datasetCode,
                              @RequestBody(required = false) java.util.Map<String, Object> body) {
        String strategy = String.valueOf(body == null ? "incremental" : body.getOrDefault("strategy", "incremental"));
        return ApiResponse.ok(syncService.trigger(datasetCode, strategy));
    }

    @GetMapping("/settings/query-mode")
    public ApiResponse<Map<String, Object>> getMode() {
        return ApiResponse.ok(modeService.snapshot());
    }

    @PostMapping("/settings/query-mode")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> setMode(@RequestBody Map<String, Object> body) {
        String mode = String.valueOf(body.getOrDefault("queryMode", "local"));
        modeService.setMode(mode);
        // 模式变了，报表与经营看板缓存一并作废
        resultCache.invalidateAll();
        return ApiResponse.ok(modeService.snapshot());
    }
}
