package com.reporthub.web;

import com.reporthub.common.ApiResponse;
import com.reporthub.domain.ContractScrollBaseline;
import com.reporthub.service.ContractScrollService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 销售合同滚动统计报表专用接口：
 * - 带分组表头与合计行的完整视图查询
 * - 2026-10 期初基线及纸质合同号映射维护（仅管理员可增删改）
 */
@RestController
@RequestMapping("/api/contract-scroll")
public class ContractScrollController {
    private final ContractScrollService service;

    public ContractScrollController(ContractScrollService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public ApiResponse<ContractScrollService.ScrollReportView> query(
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = body == null ? Map.of()
                : (Map<String, Object>) body.getOrDefault("filters", Map.of());
        int page = body == null ? 1 : toInt(body.get("page"), 1);
        int pageSize = body == null ? 200 : toInt(body.get("pageSize"), 200);
        return ApiResponse.ok(service.computeView(filters, page, pageSize));
    }

    @GetMapping("/baselines")
    public ApiResponse<List<ContractScrollBaseline>> listBaselines() {
        return ApiResponse.ok(service.listBaselines());
    }

    @PostMapping("/baselines")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ContractScrollBaseline> saveBaseline(@RequestBody ContractScrollBaseline body) {
        return ApiResponse.ok(service.saveBaseline(body));
    }

    @DeleteMapping("/baselines/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteBaseline(@PathVariable Long id) {
        service.deleteBaseline(id);
        return ApiResponse.ok(null);
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
