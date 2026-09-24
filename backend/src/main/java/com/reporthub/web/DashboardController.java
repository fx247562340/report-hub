package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.domain.DashboardWidget;
import com.reporthub.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 首页看板数据（登录用户）· 优先缓存立刻返回 */
    @GetMapping("/dashboard")
    public ApiResponse<List<Map<String, Object>>> dashboard() {
        return ApiResponse.ok(dashboardService.getForHome());
    }

    /** 强制刷新看板（同步算完） */
    @GetMapping("/dashboard/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> refresh() {
        return ApiResponse.ok(dashboardService.computeNow());
    }

    @GetMapping("/admin/dashboard/widgets")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<DashboardWidget>> widgets() {
        return ApiResponse.ok(dashboardService.listWidgets());
    }

    @PostMapping("/admin/dashboard/widgets")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<DashboardWidget>> saveWidgets(@RequestBody List<DashboardWidget> widgets) {
        return ApiResponse.ok(dashboardService.saveWidgets(widgets));
    }
}
