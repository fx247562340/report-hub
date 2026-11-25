package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.domain.DashboardWidget;
import com.reporthub.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

    /** 首页经营驾驶舱概览（月度 KPI、趋势、欠款排行、库存预警、同步健康、管理员摘要） */
    @GetMapping("/dashboard/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "false") boolean refresh,
            Authentication auth) {
        boolean isAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
        return ApiResponse.ok(dashboardService.getOverview(month, isAdmin, refresh));
    }

    /** 首页看板数据（登录用户）· 优先缓存立刻返回 */
    @GetMapping("/dashboard")
    public ApiResponse<List<Map<String, Object>>> dashboard() {
        return ApiResponse.ok(dashboardService.getForHome());
    }

    /** 强制刷新看板（同步算完） */
    @GetMapping("/dashboard/refresh")
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
