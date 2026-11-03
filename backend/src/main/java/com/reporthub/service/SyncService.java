package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.common.BizException;
import com.reporthub.domain.*;
import com.reporthub.engine.JsonPaths;
import com.reporthub.engine.UpstreamClient;
import com.reporthub.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
            m.put("parentDataset", c.getParentDataset());
            m.put("keyPath", c.getKeyPath());
            m.put("keysSql", c.getKeysSql());
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

    /** 一键全同步：列表型先跑，再 keys 型；单线程顺序执行，避免打爆上游 */
    public List<SyncJob> triggerAll() {
        List<SyncConfig> all = new java.util.ArrayList<>(configRepo.findAll());
        all.sort((a, b) -> {
            boolean ak = "keys".equalsIgnoreCase(a.getMode());
            boolean bk = "keys".equalsIgnoreCase(b.getMode());
            if (ak != bk) return ak ? 1 : -1;
            return a.getDatasetCode().compareTo(b.getDatasetCode());
        });
        List<SyncJob> jobs = new java.util.ArrayList<>();
        List<SyncConfig> plan = new java.util.ArrayList<>();
        for (SyncConfig c : all) {
            if (!Boolean.TRUE.equals(c.getEnabled())) continue;
            SyncJob job = new SyncJob();
            job.setDatasetCode(c.getDatasetCode());
            job.setStatus("queued");
            job.setTrigger("manual");
            jobs.add(jobRepo.save(job));
            plan.add(c);
        }
        pool.execute(() -> {
            for (int i = 0; i < plan.size(); i++) {
                runJob(jobs.get(i).getId(), plan.get(i));
            }
        });
        return jobs;
    }

    /** 异步触发一次全量同步 */
    public SyncJob trigger(String datasetCode) {
        SyncConfig cfg = configRepo.findById(datasetCode)
                .orElseThrow(() -> new BizException("未配置同步: " + datasetCode));
        if (!Boolean.TRUE.equals(cfg.getEnabled())) throw new BizException("同步已停用: " + datasetCode);
        SyncJob job = new SyncJob();
        job.setDatasetCode(datasetCode);
        job.setStatus("queued");
        job.setTrigger("manual");
        job = jobRepo.save(job);
        final Long jobId = job.getId();
        pool.execute(() -> runJob(jobId, cfg));
        return job;
    }

    void runJob(Long jobId, SyncConfig cfg) {
        SyncJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus("running");
        jobRepo.save(job);
        long t0 = System.currentTimeMillis();
        int rows = 0;
        int pages = 0;
        try {
            DatasetDef ds = datasetRepo.findById(cfg.getDatasetCode())
                    .orElseThrow(() -> new BizException("dataset not found: " + cfg.getDatasetCode()));
            ApiEndpointDef ep = endpointRepo.findById(ds.getEndpointCode())
                    .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
            DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();

            if ("keys".equalsIgnoreCase(cfg.getMode()) || "related".equalsIgnoreCase(cfg.getMode())) {
                rows = syncByKeys(cfg, jobId);
                pages = 1;
            } else {
            // 列表全量替换
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

    /**
     * 表级全量同步：SQL 枚举上游表全部键，再按文档接口拉原文 JSON。
     * 不依赖业务父表，便于以后任意表做主表。
     */
    int syncByKeys(SyncConfig cfg, Long jobId) throws Exception {
        String keysSql = cfg.getKeysSql();
        if (keysSql == null || keysSql.isBlank()) {
            throw new BizException("keys 模式需要 keys_sql");
        }
        String keysEp = cfg.getKeysEndpoint() == null || cfg.getKeysEndpoint().isBlank()
                ? "u9c_mo_so_contract" : cfg.getKeysEndpoint();
        ApiEndpointDef sqlEp = endpointRepo.findById(keysEp)
                .orElseThrow(() -> new BizException("keys 接口不存在: " + keysEp));
        DataSourceDef sqlSrc = dataSourceRepo.findById(sqlEp.getDataSourceCode()).orElseThrow();

        // 执行 keys SQL
        String rendered = keysSql.replace("{{limit}}", String.valueOf(Math.max(1, cfg.getMaxPages() * cfg.getPageSize())));
        Map<String, Object> sqlCtx = new java.util.HashMap<>();
        sqlCtx.put("key", "keys");
        String bodyJson = mapper.writeValueAsString(Map.of("SqlString", rendered));
        log.info("keys sql dataset={} body={}", cfg.getDatasetCode(), bodyJson);
        ApiEndpointDef sqlEpUse = copyWithBody(sqlEp, bodyJson);
        var keyResult = upstream.fetchList(sqlSrc, sqlEpUse, sqlCtx);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ObjectNode node : keyResult.rawRows()) {
            Object v = null;
            for (String cand : new String[] {"key", "k", "docno", "DocNo", "docNo", "m_docNo", "code"}) {
                if (node.has(cand) && !node.get(cand).isNull()) { v = node.get(cand); break; }
            }
            if (v == null && node.size() == 1) v = node.elements().next();
            String key = toPlainString(v);
            if (key != null && !key.isBlank()) keys.add(key);
        }

        DatasetDef ds = datasetRepo.findById(cfg.getDatasetCode())
                .orElseThrow(() -> new BizException("dataset not found: " + cfg.getDatasetCode()));
        ApiEndpointDef detailEp = endpointRepo.findById(ds.getEndpointCode())
                .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
        DataSourceDef detailSrc = dataSourceRepo.findById(detailEp.getDataSourceCode()).orElseThrow();

        // 键取到后再替换本地数据，避免失败清空
        tx.executeWithoutResult(s -> recordRepo.deleteByDatasetCode(cfg.getDatasetCode()));

        int limit = Math.max(1, cfg.getMaxPages() * Math.max(1, cfg.getPageSize()));
        List<String> keyList = new java.util.ArrayList<>(keys);
        if (keyList.size() > limit) keyList = keyList.subList(0, limit);

        int concurrency = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger rows = new AtomicInteger();
        final Object flushLock = new Object();
        final java.util.List<SyncRecord> pending = new java.util.ArrayList<>();

        java.util.function.Consumer<List<SyncRecord>> flush = (batch) -> {
            if (batch.isEmpty()) return;
            final List<SyncRecord> slice = new java.util.ArrayList<>(batch);
            tx.executeWithoutResult(s -> recordRepo.saveAll(slice));
        };

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (String key : keyList) {
            futures.add(pool.submit(() -> {
                Map<String, Object> ctx = new java.util.HashMap<>();
                ctx.put("key", key);
                ctx.put("id", key);
                try {
                    var result = upstream.fetchList(detailSrc, detailEp, ctx);
                    List<ObjectNode> rawRows = result.rawRows();
                    if (rawRows.size() > 50) {
                        log.warn("keys detail returned {} rows for one key, skip dataset={} key={}",
                                rawRows.size(), cfg.getDatasetCode(), key);
                        return;
                    }
                    for (ObjectNode node : rawRows) {
                        String err = detectErrorEnvelope(node);
                        if (err != null) throw new BizException("上游返回错误，未入库: " + err);
                        SyncRecord rec = new SyncRecord();
                        rec.setDatasetCode(cfg.getDatasetCode());
                        rec.setPayload(node.toString());
                        rec.setSourceKey(extractKey(node) != null ? extractKey(node) : key);
                        rec.setBatchId(jobId);
                        rec.setSyncedAt(Instant.now());
                        rows.incrementAndGet();
                        List<SyncRecord> toFlush = null;
                        synchronized (flushLock) {
                            pending.add(rec);
                            if (pending.size() >= 100) {
                                toFlush = new java.util.ArrayList<>(pending);
                                pending.clear();
                            }
                        }
                        if (toFlush != null) flush.accept(toFlush);
                    }
                } catch (Exception e) {
                    log.warn("keys fetch failed dataset={} key={} err={}", cfg.getDatasetCode(), key, e.getMessage());
                }
            }));
        }
        try {
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        List<SyncRecord> rest;
        synchronized (flushLock) {
            rest = new java.util.ArrayList<>(pending);
            pending.clear();
        }
        flush.accept(rest);
        log.info("keys sync done dataset={} keys={} rows={}", cfg.getDatasetCode(), keyList.size(), rows.get());
        return rows.get();
    }

    private ApiEndpointDef copyWithBody(ApiEndpointDef ep, String body) {
        ApiEndpointDef c = new ApiEndpointDef();
        c.setCode(ep.getCode());
        c.setDataSourceCode(ep.getDataSourceCode());
        c.setName(ep.getName());
        c.setMethod(ep.getMethod());
        c.setPath(ep.getPath());
        c.setQueryTemplate(ep.getQueryTemplate());
        c.setBodyTemplate(body);
        c.setBodyType("json");
        c.setHeadersTemplate(ep.getHeadersTemplate());
        c.setListPath(ep.getListPath());
        c.setTotalPath(ep.getTotalPath());
        c.setPagination(ep.getPagination());
        return c;
    }

    private static String toPlainString(Object v) {
        if (v == null) return null;
        if (v instanceof com.fasterxml.jackson.databind.JsonNode jn) {
            if (jn.isValueNode()) return jn.asText(null);
            return jn.toString();
        }
        return String.valueOf(v);
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
        // U9 包装异常：{"xxx.Datas[0].DocNo": {"_errors": [...]}}
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue() != null && e.getValue().has("_errors")) {
                return "上游异常: " + e.getKey();
            }
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
