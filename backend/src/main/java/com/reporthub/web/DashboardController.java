package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.service.DashboardService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 首页经营驾驶舱概览（月度 KPI、趋势、欠款排行、库存预警、同步健康） */
    @GetMapping("/dashboard/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(dashboardService.getOverview(month, refresh));
    }
}
