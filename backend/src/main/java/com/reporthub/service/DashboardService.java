package com.reporthub.service;

import com.reporthub.domain.SyncConfig;
import com.reporthub.repo.SyncConfigRepo;
import com.reporthub.repo.SyncJobRepo;
import com.reporthub.repo.SyncRecordRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class DashboardService {
    private final ReportResultCache resultCache;
    private final QueryModeService queryModeService;
    private final ContractScrollService contractScrollService;
    private final SyncConfigRepo syncConfigRepo;
    private final SyncJobRepo syncJobRepo;
    private final SyncRecordRepo syncRecordRepo;

    public DashboardService(ReportResultCache resultCache,
                            QueryModeService queryModeService,
                            ContractScrollService contractScrollService,
                            SyncConfigRepo syncConfigRepo,
                            SyncJobRepo syncJobRepo,
                            SyncRecordRepo syncRecordRepo) {
        this.resultCache = resultCache;
        this.queryModeService = queryModeService;
        this.contractScrollService = contractScrollService;
        this.syncConfigRepo = syncConfigRepo;
        this.syncJobRepo = syncJobRepo;
        this.syncRecordRepo = syncRecordRepo;
    }

    /** 经营驾驶舱全量概览（镀锌经营指标、月度趋势、欠款榜、负库存预警、同步健康） */
    public Map<String, Object> getOverview(String month, boolean forceRefresh) {
        if (forceRefresh) {
            resultCache.invalidateReport(ContractScrollService.REPORT_CODE);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryMode", queryModeService.mode());
        out.put("generatedAt", Instant.now().toString());
        out.put("syncHealth", buildSyncHealth());
        try {
            out.put("executive", contractScrollService.computeExecutiveOverview(month));
        } catch (Exception e) {
            out.put("executive", Map.of("error", e.getMessage() == null ? "经营数据暂不可用" : e.getMessage()));
        }
        return out;
    }

    private Map<String, Object> buildSyncHealth() {
        List<SyncConfig> configs = syncConfigRepo.findAll();
        int total = configs.size();
        int enabled = 0;
        int failed = 0;
        int running = 0;
        Instant latest = null;
        List<String> failedNames = new ArrayList<>();
        for (SyncConfig c : configs) {
            if (Boolean.TRUE.equals(c.getEnabled())) enabled++;
            Instant ts = syncRecordRepo.findLastSyncedAt(c.getDatasetCode());
            if (ts != null && (latest == null || ts.isAfter(latest))) latest = ts;
            var lastJob = syncJobRepo.findByDatasetCodeOrderByStartedAtDesc(c.getDatasetCode()).stream().findFirst();
            if (lastJob.isPresent()) {
                String st = lastJob.get().getStatus();
                if ("failed".equalsIgnoreCase(st)) {
                    failed++;
                    if (failedNames.size() < 4) failedNames.add(c.getDatasetCode());
                } else if ("running".equalsIgnoreCase(st) || "queued".equalsIgnoreCase(st)) {
                    running++;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("enabled", enabled);
        m.put("failed", failed);
        m.put("running", running);
        m.put("lastSyncedAt", latest == null ? null : latest.toString());
        m.put("failedDatasets", failedNames);
        return m;
    }
}
