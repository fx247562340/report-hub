package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.service.QueryModeService;
import com.reporthub.service.SyncService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SyncController {
    private final SyncService syncService;
    private final QueryModeService modeService;
    private final com.reporthub.service.DashboardService dashboardService;

    public SyncController(SyncService syncService, QueryModeService modeService,
                          com.reporthub.service.DashboardService dashboardService) {
        this.syncService = syncService;
        this.modeService = modeService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/sync/status")
    public ApiResponse<List<Map<String, Object>>> status() {
        return ApiResponse.ok(syncService.listStatus());
    }

    @GetMapping("/sync/jobs")
    public ApiResponse<?> jobs() {
        return ApiResponse.ok(syncService.listJobs());
    }

    @PostMapping("/sync/{datasetCode}/schedule")
    public ApiResponse<?> updateSchedule(@PathVariable String datasetCode, @RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.getOrDefault("scheduleType", "manual"));
        String time = body.get("scheduleTime") == null ? null : String.valueOf(body.get("scheduleTime"));
        Integer weekday = body.get("scheduleWeekday") == null ? null : Integer.valueOf(String.valueOf(body.get("scheduleWeekday")));
        Integer interval = body.get("scheduleIntervalMin") == null ? null : Integer.valueOf(String.valueOf(body.get("scheduleIntervalMin")));
        return ApiResponse.ok(syncService.updateSchedule(datasetCode, type, time, weekday, interval));
    }

    @PostMapping("/sync/{datasetCode}/stop")
    public ApiResponse<?> stop(@PathVariable String datasetCode) {
        syncService.requestCancel(datasetCode);
        return ApiResponse.ok(Map.of("cancelled", datasetCode));
    }

    @PostMapping("/sync/{datasetCode}/enabled")
    public ApiResponse<?> setEnabled(@PathVariable String datasetCode, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        syncService.setEnabled(datasetCode, enabled);
        return ApiResponse.ok(Map.of("datasetCode", datasetCode, "enabled", enabled));
    }

    @PostMapping("/sync/run-all")
    public ApiResponse<?> runAll(@RequestBody(required = false) java.util.Map<String, Object> body) {
        String strategy = String.valueOf(body == null ? "incremental" : body.getOrDefault("strategy", "incremental"));
        return ApiResponse.ok(syncService.triggerAll(strategy));
    }

    @PostMapping("/sync/{datasetCode}/run")
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
    public ApiResponse<Map<String, Object>> setMode(@RequestBody Map<String, Object> body) {
        String mode = String.valueOf(body.getOrDefault("queryMode", "local"));
        modeService.setMode(mode);
        // 模式变了，首页看板缓存按旧模式算的必须作废
        dashboardService.invalidate();
        return ApiResponse.ok(modeService.snapshot());
    }
}
