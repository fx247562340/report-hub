package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.common.BizException;
import com.reporthub.domain.*;
import com.reporthub.engine.UpstreamClient;
import com.reporthub.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 把上游列表接口的原始行同步到本地 sync_records（JSONB 原样保存）。
 * 不做字段映射；查询时仍走 field_mappings。
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final SyncConfigRepo configRepo;
    private final SyncJobRepo jobRepo;
    private final SyncRecordRepo recordRepo;
    private final DatasetRepo datasetRepo;
    private final ApiEndpointRepo endpointRepo;
    private final DataSourceRepo dataSourceRepo;
    private final UpstreamClient upstream;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync-runner");
        t.setDaemon(true);
        return t;
    });

    public SyncService(SyncConfigRepo configRepo, SyncJobRepo jobRepo, SyncRecordRepo recordRepo,
                       DatasetRepo datasetRepo, ApiEndpointRepo endpointRepo, DataSourceRepo dataSourceRepo,
                       UpstreamClient upstream, ObjectMapper mapper, TransactionTemplate tx) {
        this.configRepo = configRepo;
        this.jobRepo = jobRepo;
        this.recordRepo = recordRepo;
        this.datasetRepo = datasetRepo;
        this.endpointRepo = endpointRepo;
        this.dataSourceRepo = dataSourceRepo;
        this.upstream = upstream;
        this.mapper = mapper;
        this.tx = tx;
    }

    public List<Map<String, Object>> listStatus() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SyncConfig c : configRepo.findAll()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("datasetCode", c.getDatasetCode());
            DatasetDef ds = datasetRepo.findById(c.getDatasetCode()).orElse(null);
            m.put("name", ds != null ? ds.getName() : c.getDatasetCode());
            m.put("endpointCode", ds != null ? ds.getEndpointCode() : null);
            m.put("enabled", c.getEnabled());
            m.put("mode", c.getMode());
            m.put("pageSize", c.getPageSize());
            m.put("maxPages", c.getMaxPages());
            m.put("recordCount", recordRepo.countByDatasetCode(c.getDatasetCode()));
            m.put("lastSyncedAt", recordRepo.findLastSyncedAt(c.getDatasetCode()));
            jobRepo.findByDatasetCodeOrderByStartedAtDesc(c.getDatasetCode()).stream().findFirst().ifPresent(j -> {
                m.put("lastJobStatus", j.getStatus());
                m.put("lastJobRows", j.getRowCount());
                m.put("lastJobError", j.getError());
                m.put("lastJobDurationMs", j.getDurationMs());
            });
            out.add(m);
        }
        return out;
    }

    public List<SyncJob> listJobs() {
        return jobRepo.findTop50ByOrderByStartedAtDesc();
    }

    /** 异步触发一次全量同步 */
    public SyncJob trigger(String datasetCode) {
        SyncConfig cfg = configRepo.findById(datasetCode)
                .orElseThrow(() -> new BizException("未配置同步: " + datasetCode));
        if (!Boolean.TRUE.equals(cfg.getEnabled())) throw new BizException("同步已停用: " + datasetCode);
        SyncJob job = new SyncJob();
        job.setDatasetCode(datasetCode);
        job.setStatus("running");
        job.setTrigger("manual");
        job = jobRepo.save(job);
        final Long jobId = job.getId();
        pool.execute(() -> runJob(jobId, cfg));
        return job;
    }

    void runJob(Long jobId, SyncConfig cfg) {
        SyncJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        long t0 = System.currentTimeMillis();
        int rows = 0;
        int pages = 0;
        try {
            DatasetDef ds = datasetRepo.findById(cfg.getDatasetCode())
                    .orElseThrow(() -> new BizException("dataset not found: " + cfg.getDatasetCode()));
            ApiEndpointDef ep = endpointRepo.findById(ds.getEndpointCode())
                    .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
            DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();

            // 全量替换（需要事务执行 @Modifying delete）
            tx.executeWithoutResult(s -> recordRepo.deleteByDatasetCode(cfg.getDatasetCode()));

            int pageSize = cfg.getPageSize();
            java.util.List<SyncRecord> batch = new java.util.ArrayList<>();
            for (int page = 1; page <= cfg.getMaxPages(); page++) {
                Map<String, Object> ctx = new java.util.LinkedHashMap<>();
                ctx.put("page", page);
                ctx.put("pageSize", pageSize);
                ctx.put("size", pageSize);
                ctx.put("limit", pageSize);
                ctx.put("current", page);
                var result = upstream.fetchList(src, ep, ctx);
                List<ObjectNode> raw = result.rawRows();
                pages++;
                for (ObjectNode node : raw) {
                    String err = detectErrorEnvelope(node);
                    if (err != null) {
                        throw new BizException("上游返回错误，未入库: " + err);
                    }
                }
                for (ObjectNode node : raw) {
                    SyncRecord rec = new SyncRecord();
                    rec.setDatasetCode(cfg.getDatasetCode());
                    rec.setPayload(node.toString());
                    rec.setSourceKey(extractKey(node));
                    rec.setBatchId(jobId);
                    rec.setSyncedAt(Instant.now());
                    batch.add(rec);
                    rows++;
                }
                if (batch.size() >= 500) {
                    final List<SyncRecord> toSave = batch;
                    tx.executeWithoutResult(s -> recordRepo.saveAll(toSave));
                    batch = new java.util.ArrayList<>();
                }
                if (raw.size() < pageSize) break;
            }
            if (!batch.isEmpty()) {
                final List<SyncRecord> toSave = batch;
                tx.executeWithoutResult(s -> recordRepo.saveAll(toSave));
            }

            job.setStatus("success");
            job.setRowCount(rows);
            job.setPageCount(pages);
            job.setDurationMs((int) (System.currentTimeMillis() - t0));
            job.setFinishedAt(Instant.now());
            jobRepo.save(job);
            log.info("sync ok dataset={} rows={} pages={} ms={}", cfg.getDatasetCode(), rows, pages, job.getDurationMs());
        } catch (Exception e) {
            job.setStatus("failed");
            job.setRowCount(rows);
            job.setPageCount(pages);
            job.setDurationMs((int) (System.currentTimeMillis() - t0));
            job.setFinishedAt(Instant.now());
            job.setError(e.getMessage());
            jobRepo.save(job);
            log.warn("sync failed dataset={} err={}", cfg.getDatasetCode(), e.getMessage());
        }
    }

    /** 上游把登录失败/业务错误包成一条 JSON 时，不能当业务行入库 */
    static String detectErrorEnvelope(ObjectNode node) {
        if (node.has("success") && node.get("success").isBoolean() && !node.get("success").asBoolean()) {
            return node.path("msg").asText(node.path("error").asText("success=false"));
        }
        if (node.has("code") && node.get("code").isNumber() && node.get("code").asInt() >= 400
                && (node.has("msg") || node.has("message")) && node.size() <= 5) {
            return node.path("msg").asText(node.path("message").asText("code>=400"));
        }
        return null;
    }

    /** 尽力提取业务键，便于去重/排查 */
    static String extractKey(ObjectNode node) {
        String[] keys = {"id", "Id", "ID", "docNo", "DocNo", "hangNo", "orderNo", "code", "barCode", "productionOrderNo"};
        for (String k : keys) {
            if (node.has(k) && !node.get(k).isNull()) {
                String v = node.get(k).asText("");
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }
}
