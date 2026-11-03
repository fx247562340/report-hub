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

    public SyncController(SyncService syncService, QueryModeService modeService) {
        this.syncService = syncService;
        this.modeService = modeService;
    }

    @GetMapping("/sync/status")
    public ApiResponse<List<Map<String, Object>>> status() {
        return ApiResponse.ok(syncService.listStatus());
    }

    @GetMapping("/sync/jobs")
    public ApiResponse<?> jobs() {
        return ApiResponse.ok(syncService.listJobs());
    }

    @PostMapping("/sync/{datasetCode}/run")
    public ApiResponse<?> run(@PathVariable String datasetCode) {
        return ApiResponse.ok(syncService.trigger(datasetCode));
    }

    @GetMapping("/settings/query-mode")
    public ApiResponse<Map<String, Object>> getMode() {
        return ApiResponse.ok(modeService.snapshot());
    }

    @PostMapping("/settings/query-mode")
    public ApiResponse<Map<String, Object>> setMode(@RequestBody Map<String, Object> body) {
        String mode = String.valueOf(body.getOrDefault("queryMode", "realtime"));
        modeService.setMode(mode);
        return ApiResponse.ok(modeService.snapshot());
    }
}
