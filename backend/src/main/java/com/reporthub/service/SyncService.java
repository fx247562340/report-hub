package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.common.BizException;
import com.reporthub.domain.*;
import com.reporthub.engine.JsonPaths;
import com.reporthub.engine.TemplateRenderer;
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
    /** 请求取消的同步任务（datasetCode） */
    private final java.util.Set<String> cancelRequested =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 停止某数据集的同步（排队中直接跳过，运行中尽快退出） */
    public void requestCancel(String datasetCode) {
        cancelRequested.add(datasetCode);
        // 打断运行中的任务
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains("sync-runner") || t.getName().contains("pool-")) {
                // 不强制 interrupt 全池；仅标记后任务自查
            }
        }
    }

    public boolean isCancelRequested(String datasetCode) {
        return cancelRequested.contains(datasetCode);
    }

    /** 更新调度配置 */
    public SyncConfig updateSchedule(String datasetCode, String scheduleType, String scheduleTime,
                                     Integer scheduleWeekday, Integer scheduleIntervalMin) {
        if (scheduleType == null) scheduleType = "manual";
        if (!java.util.Set.of("manual", "interval", "daily", "weekly").contains(scheduleType)) {
            throw new BizException("schedule_type 必须是 manual|interval|daily|weekly");
        }
        SyncConfig cfg = configRepo.findById(datasetCode)
                .orElseThrow(() -> new BizException("未配置同步: " + datasetCode));
        cfg.setScheduleType(scheduleType);
        cfg.setScheduleTime(scheduleTime);
        cfg.setScheduleWeekday(scheduleWeekday);
        cfg.setScheduleIntervalMin(scheduleIntervalMin);
        cfg.setUpdatedAt(Instant.now());
        cfg.setNextScheduleRun(computeNextRun(cfg));
        return configRepo.save(cfg);
    }

    /** 下次应执行时间；manual 返回 null */
    public Instant computeNextRun(SyncConfig cfg) {
        if (cfg.getScheduleType() == null || "manual".equals(cfg.getScheduleType())) return null;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        switch (cfg.getScheduleType()) {
            case "interval": {
                int min = cfg.getScheduleIntervalMin() == null || cfg.getScheduleIntervalMin() <= 0
                        ? 60 : cfg.getScheduleIntervalMin();
                Instant base = cfg.getLastScheduleRun() == null ? Instant.now() : cfg.getLastScheduleRun();
                return base.plusSeconds(min * 60L);
            }
            case "daily": {
                java.time.LocalTime time = parseTime(cfg.getScheduleTime());
                java.time.ZonedDateTime t = now.toLocalDate().atTime(time).atZone(zone);
                if (!t.isAfter(now)) t = t.plusDays(1);
                return t.toInstant();
            }
            case "weekly": {
                java.time.LocalTime time = parseTime(cfg.getScheduleTime());
                int wd = cfg.getScheduleWeekday() == null ? 1 : cfg.getScheduleWeekday(); // 1=Mon
                java.time.DayOfWeek target = java.time.DayOfWeek.of(((wd - 1) % 7) + 1);
                java.time.ZonedDateTime t = now.toLocalDate().atTime(time).atZone(zone);
                while (t.getDayOfWeek() != target || !t.isAfter(now)) {
                    t = t.plusDays(1);
                }
                return t.toInstant();
            }
            default:
                return null;
        }
    }

    private static java.time.LocalTime parseTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return java.time.LocalTime.of(2, 0);
        String[] p = hhmm.trim().split(":");
        int h = Integer.parseInt(p[0]);
        int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        return java.time.LocalTime.of(Math.min(23, Math.max(0, h)), Math.min(59, Math.max(0, m)));
    }

    /** 调度器：到点则自动触发全量同步 */
    public int tickSchedule() {
        int fired = 0;
        Instant now = Instant.now();
        List<SyncConfig> due = new java.util.ArrayList<>();
        for (SyncConfig cfg : configRepo.findAll()) {
            if (!Boolean.TRUE.equals(cfg.getEnabled())) continue;
            if ("manual".equals(cfg.getScheduleType())) continue;
            Instant next = cfg.getNextScheduleRun() != null ? cfg.getNextScheduleRun() : computeNextRun(cfg);
            if (next == null) continue;
            if (!next.isAfter(now)) {
                due.add(cfg);
            } else if (cfg.getNextScheduleRun() == null) {
                cfg.setNextScheduleRun(next);
                configRepo.save(cfg);
            }
        }
        // 与一键全同步同序：列表型先跑、keys 型按编码（complete→mo→so 即父→子），避免链式取键落空
        due.sort((a, b) -> {
            boolean ak = "keys".equalsIgnoreCase(a.getMode()) || "related".equalsIgnoreCase(a.getMode());
            boolean bk = "keys".equalsIgnoreCase(b.getMode()) || "related".equalsIgnoreCase(b.getMode());
            if (ak != bk) return ak ? 1 : -1;
            return a.getDatasetCode().compareTo(b.getDatasetCode());
        });
        for (SyncConfig cfg : due) {
            cfg.setLastScheduleRun(now);
            cfg.setNextScheduleRun(computeNextRun(cfg));
            configRepo.save(cfg);
            try {
                trigger(cfg.getDatasetCode(), "incremental");
                fired++;
                log.info("schedule fire dataset={} type={}", cfg.getDatasetCode(), cfg.getScheduleType());
            } catch (Exception e) {
                log.warn("schedule trigger failed {}: {}", cfg.getDatasetCode(), e.getMessage());
            }
        }
        return fired;
    }

    public void setEnabled(String datasetCode, boolean enabled) {
        SyncConfig cfg = configRepo.findById(datasetCode)
                .orElseThrow(() -> new BizException("未配置同步: " + datasetCode));
        cfg.setEnabled(enabled);
        cfg.setUpdatedAt(Instant.now());
        configRepo.save(cfg);
        if (!enabled) requestCancel(datasetCode);
    }

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
            m.put("scheduleType", c.getScheduleType());
            m.put("scheduleTime", c.getScheduleTime());
            m.put("scheduleWeekday", c.getScheduleWeekday());
            m.put("scheduleIntervalMin", c.getScheduleIntervalMin());
            m.put("lastScheduleRun", c.getLastScheduleRun());
            m.put("nextScheduleRun", c.getNextScheduleRun());
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
    public List<SyncJob> triggerAll(String strategy) {
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
            job.setTrigger("full".equalsIgnoreCase(strategy) ? "manual-full" : "manual");
            jobs.add(jobRepo.save(job));
            plan.add(c);
        }
        final String strat = strategy;
        pool.execute(() -> {
            for (int i = 0; i < plan.size(); i++) {
                if (isCancelRequested(plan.get(i).getDatasetCode())) {
                    SyncJob j = jobRepo.findById(jobs.get(i).getId()).orElse(null);
                    if (j != null) {
                        j.setStatus("cancelled");
                        j.setFinishedAt(Instant.now());
                        j.setError("已取消");
                        jobRepo.save(j);
                    }
                    continue;
                }
                runJob(jobs.get(i).getId(), plan.get(i), strat);
            }
        });
        return jobs;
    }

    /** 异步触发一次同步；strategy = incremental（默认，只取新增并 upsert）| full（清库重写） */
    public SyncJob trigger(String datasetCode, String strategy) {
        SyncConfig cfg = configRepo.findById(datasetCode)
                .orElseThrow(() -> new BizException("未配置同步: " + datasetCode));
        if (!Boolean.TRUE.equals(cfg.getEnabled())) throw new BizException("同步已停用: " + datasetCode);
        cancelRequested.remove(datasetCode);
        SyncJob job = new SyncJob();
        job.setDatasetCode(datasetCode);
        job.setStatus("queued");
        job.setTrigger("full".equalsIgnoreCase(strategy) ? "manual-full" : "manual");
        job = jobRepo.save(job);
        final Long jobId = job.getId();
        final String strat = strategy;
        pool.execute(() -> runJob(jobId, cfg, strat));
        return job;
    }

    void runJob(Long jobId, SyncConfig cfg, String strategy) {
        SyncJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        if (isCancelRequested(cfg.getDatasetCode())) {
            job.setStatus("cancelled");
            job.setFinishedAt(Instant.now());
            job.setError("已取消");
            jobRepo.save(job);
            return;
        }
        job.setStatus("running");
        jobRepo.save(job);
        long t0 = System.currentTimeMillis();
        int rows = 0;
        int pages = 0;
        // 增量：以上次写入时间为界（回看 2 天容错）；没有本地历史则按全量
        boolean incremental = !"full".equalsIgnoreCase(strategy);
        Instant sinceTs = incremental ? recordRepo.findLastSyncedAt(cfg.getDatasetCode()) : null;
        if (sinceTs == null) incremental = false;
        String sinceDate = sinceTs == null ? null
                : java.time.LocalDate.ofInstant(sinceTs.minus(java.time.Duration.ofDays(2)),
                        java.time.ZoneId.systemDefault()).toString();
        try {
            DatasetDef ds = datasetRepo.findById(cfg.getDatasetCode())
                    .orElseThrow(() -> new BizException("dataset not found: " + cfg.getDatasetCode()));
            ApiEndpointDef ep = endpointRepo.findById(ds.getEndpointCode())
                    .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
            DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();

            if ("keys".equalsIgnoreCase(cfg.getMode()) || "related".equalsIgnoreCase(cfg.getMode())) {
                rows = syncByKeys(cfg, jobId, incremental, sinceDate, sinceTs);
                pages = 1;
            } else {
            // 全量：整表替换；增量：只取新增并按行 upsert
            if (!incremental) {
            tx.executeWithoutResult(s -> recordRepo.deleteByDatasetCode(cfg.getDatasetCode()));
            }
            ApiEndpointDef epUse = incremental ? applyIncremental(ep, cfg.getIncrementalFilter(), sinceDate) : ep;
            int pageSize = cfg.getPageSize();
            java.util.List<SyncRecord> batch = new java.util.ArrayList<>();
            java.util.Map<String, Long> existing = incremental ? loadKeyIds(cfg.getDatasetCode()) : java.util.Map.of();
            for (int page = 1; page <= cfg.getMaxPages(); page++) {
                if (isCancelRequested(cfg.getDatasetCode())) {
                    throw new InterruptedException("cancelled");
                }
                Map<String, Object> ctx = new java.util.LinkedHashMap<>();
                ctx.put("page", page);
                ctx.put("pageSize", pageSize);
                ctx.put("size", pageSize);
                ctx.put("limit", pageSize);
                ctx.put("current", page);
                var result = upstream.fetchList(src, epUse, ctx);
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
                    if (incremental && rec.getSourceKey() != null) {
                        Long id = existing.get(rec.getSourceKey());
                        if (id != null) rec.setId(id);
                    }
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
            boolean cancelled = isCancelRequested(cfg.getDatasetCode())
                    || e instanceof InterruptedException
                    || (e.getMessage() != null && e.getMessage().contains("interrupt"));
            job.setStatus(cancelled ? "cancelled" : "failed");
            job.setRowCount(rows);
            job.setPageCount(pages);
            job.setDurationMs((int) (System.currentTimeMillis() - t0));
            job.setFinishedAt(Instant.now());
            job.setError(cancelled ? "已取消" : e.getMessage());
            jobRepo.save(job);
            log.warn("sync {} dataset={} err={}", job.getStatus(), cfg.getDatasetCode(), e.getMessage());
        } finally {
            cancelRequested.remove(cfg.getDatasetCode());
        }
    }

    /**
     * 表级全量同步：SQL 枚举上游表全部键，再按文档接口拉原文 JSON。
     * 不依赖业务父表，便于以后任意表做主表。
     */
    int syncByKeys(SyncConfig cfg, Long jobId, boolean incremental, String sinceDate, Instant sinceTs) throws Exception {
        String keysSql = cfg.getKeysSql();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (keysSql != null && !keysSql.isBlank()) {
            keys.addAll(fetchKeysBySql(cfg, keysSql));
        } else if (cfg.getParentDataset() != null && !cfg.getParentDataset().isBlank()) {
            keys.addAll(fetchKeysFromParent(cfg, incremental, sinceTs));
        } else {
            keys.addAll(fetchKeysByList(cfg, incremental, sinceDate));
        }
        // 键为空多半是键源/上游异常，不能清掉本地数据
        if (keys.isEmpty()) {
            if (incremental) return 0;
            throw new BizException("键为空，已保留本地数据（检查键源配置与上游返回）");
        }
        DatasetDef ds = datasetRepo.findById(cfg.getDatasetCode())
                .orElseThrow(() -> new BizException("dataset not found: " + cfg.getDatasetCode()));
        ApiEndpointDef detailEp = endpointRepo.findById(ds.getEndpointCode())
                .orElseThrow(() -> new BizException("endpoint not found: " + ds.getEndpointCode()));
        DataSourceDef detailSrc = dataSourceRepo.findById(detailEp.getDataSourceCode()).orElseThrow();

        // 键取到后再替换本地数据，避免失败清空；增量不删库，按行 upsert
        final java.util.Map<String, Long> existing = incremental ? loadKeyIds(cfg.getDatasetCode()) : java.util.Map.of();
        if (!incremental) {
            tx.executeWithoutResult(s -> recordRepo.deleteByDatasetCode(cfg.getDatasetCode()));
        }

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
            if (isCancelRequested(cfg.getDatasetCode())) break;
            futures.add(pool.submit(() -> {
                if (isCancelRequested(cfg.getDatasetCode())) return;
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
                        String sk = extractKey(node);
                        rec.setSourceKey(sk != null && !sk.startsWith("h:") ? sk : key);
                        rec.setBatchId(jobId);
                        rec.setSyncedAt(Instant.now());
                        if (incremental && rec.getSourceKey() != null) {
                            Long id = existing.get(rec.getSourceKey());
                            if (id != null) rec.setId(id);
                        }
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
        if (isCancelRequested(cfg.getDatasetCode())) {
            throw new InterruptedException("cancelled");
        }
        log.info("keys sync done dataset={} keys={} rows={}", cfg.getDatasetCode(), keyList.size(), rows.get());
        return rows.get();
    }

    /** 键源一：上游 SQL 枚举（QueryInfoBySql，U9 已下架，保留给其他源） */
    private LinkedHashSet<String> fetchKeysBySql(SyncConfig cfg, String keysSql) throws Exception {
        String keysEp = cfg.getKeysEndpoint() == null || cfg.getKeysEndpoint().isBlank()
                ? "u9c_mo_so_contract" : cfg.getKeysEndpoint();
        ApiEndpointDef sqlEp = endpointRepo.findById(keysEp)
                .orElseThrow(() -> new BizException("keys 接口不存在: " + keysEp));
        DataSourceDef sqlSrc = dataSourceRepo.findById(sqlEp.getDataSourceCode()).orElseThrow();
        String rendered = keysSql.replace("{{limit}}", String.valueOf(Math.max(1, cfg.getMaxPages() * cfg.getPageSize())));
        Map<String, Object> sqlCtx = new java.util.HashMap<>();
        sqlCtx.put("key", "keys");
        String bodyJson = mapper.writeValueAsString(Map.of("SqlString", rendered));
        log.info("keys sql dataset={} body={}", cfg.getDatasetCode(), bodyJson);
        var keyResult = upstream.fetchList(sqlSrc, copyWithBody(sqlEp, bodyJson), sqlCtx);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ObjectNode node : keyResult.rawRows()) addKeyFromNode(keys, node);
        return keys;
    }

    /** 键源二：父数据集本地行的字段（如 完工报告的 m_mO.m_code）；增量只取上次同步后新写入的父行 */
    private LinkedHashSet<String> fetchKeysFromParent(SyncConfig cfg, boolean incremental, Instant sinceTs) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String path = cfg.getKeyPath();
        if (path == null || path.isBlank()) {
            throw new BizException("parent_dataset 键源需要 key_path");
        }
        Instant floor = incremental && sinceTs != null ? sinceTs.minus(java.time.Duration.ofDays(1)) : null;
        for (SyncRecord rec : recordRepo.findByDatasetCodeOrderByIdAsc(cfg.getParentDataset())) {
            if (floor != null && rec.getSyncedAt() != null && rec.getSyncedAt().isBefore(floor)) continue;
            try {
                ObjectNode node = (ObjectNode) mapper.readTree(rec.getPayload());
                String k = toPlainString(com.reporthub.engine.JsonPaths.read(node, path));
                if (k != null && !k.isBlank()) keys.add(k);
            } catch (Exception ignored) {
            }
        }
        log.info("keys from parent dataset={} parent={} path={} keys={} incremental={}",
                cfg.getDatasetCode(), cfg.getParentDataset(), path, keys.size(), incremental);
        return keys;
    }

    /** 键源三：列表接口枚举单号（CommonEntity/Query 等）；增量给枚举请求附加时间过滤 */
    private LinkedHashSet<String> fetchKeysByList(SyncConfig cfg, boolean incremental, String sinceDate) {
        String keysEp = cfg.getKeysEndpoint();
        if (keysEp == null || keysEp.isBlank()) {
            throw new BizException("keys 模式需要 keys_sql / parent_dataset / keys_endpoint 之一");
        }
        ApiEndpointDef ep = endpointRepo.findById(keysEp)
                .orElseThrow(() -> new BizException("键源接口不存在: " + keysEp));
        if (incremental) ep = applyIncremental(ep, cfg.getIncrementalFilter(), sinceDate);
        DataSourceDef src = dataSourceRepo.findById(ep.getDataSourceCode()).orElseThrow();
        int limit = Math.max(1, cfg.getMaxPages() * Math.max(1, cfg.getPageSize()));
        Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put("page", 1);
        ctx.put("pageSize", limit);
        var result = upstream.fetchList(src, ep, ctx);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ObjectNode node : result.rawRows()) addKeyFromNode(keys, node);
        log.info("keys from list dataset={} ep={} keys={}", cfg.getDatasetCode(), keysEp, keys.size());
        return keys;
    }

    private void addKeyFromNode(LinkedHashSet<String> keys, ObjectNode node) {
        Object v = null;
        for (String cand : new String[] {"key", "k", "docno", "DocNo", "docNo", "m_docNo", "code"}) {
            if (node.has(cand) && !node.get(cand).isNull()) { v = node.get(cand); break; }
        }
        if (v == null && node.size() == 1) v = node.elements().next();
        String key = toPlainString(v);
        if (key != null && !key.isBlank()) keys.add(key);
    }

    /** 现有行的 source_key → id，供增量 upsert 定位更新 */
    private java.util.Map<String, Long> loadKeyIds(String datasetCode) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (Object[] row : recordRepo.findIdKeyByDatasetCode(datasetCode)) {
            out.put(String.valueOf(row[1]), ((Number) row[0]).longValue());
        }
        return out;
    }

    /**
     * 增量取数：把 sync_configs.incremental_filter 合并进请求。
     * 支持 {"queryMerge":{...}} / {"bodyMerge":{...}} / {"filtersAppend":[...]} / {"filtersAppendMonths":{...}}；
     * 值可用 {{sinceDate}}(yyyy-mm-dd) / {{sinceCompact}}(yyyymmdd) / {{sinceYM}}(yyyymm)。
     * 注意模板里 {{pageSize}} 这类占位符不是合法 JSON，合并前先打标记、合并后还原。
     */
    private ApiEndpointDef applyIncremental(ApiEndpointDef ep, String specJson, String sinceDate) {
        if (ep == null || specJson == null || specJson.isBlank() || sinceDate == null) return ep;
        try {
            Map<String, Object> spec = mapper.readValue(specJson, Map.class);
            Map<String, Object> vars = new java.util.HashMap<>();
            vars.put("sinceDate", sinceDate);
            vars.put("sinceCompact", sinceDate.replace("-", ""));
            vars.put("sinceYM", sinceDate.length() >= 7 ? sinceDate.substring(0, 7).replace("-", "") : sinceDate.replace("-", ""));
            ApiEndpointDef c = copyOf(ep);
            if (spec.get("queryMerge") instanceof Map<?, ?> qm) {
                c.setQueryTemplate(patchJson(c.getQueryTemplate(), obj -> {
                    for (Map.Entry<?, ?> e : qm.entrySet()) {
                        obj.put(String.valueOf(e.getKey()), TemplateRenderer.render(String.valueOf(e.getValue()), vars));
                    }
                }));
            }
            if (spec.get("bodyMerge") instanceof Map<?, ?> bm) {
                c.setBodyTemplate(patchJson(c.getBodyTemplate(), obj -> {
                    for (Map.Entry<?, ?> e : bm.entrySet()) {
                        obj.put(String.valueOf(e.getKey()), TemplateRenderer.render(String.valueOf(e.getValue()), vars));
                    }
                }));
            }
            if (spec.get("filtersAppend") instanceof List<?> fa) {
                String rendered = TemplateRenderer.render(mapper.writeValueAsString(fa), vars);
                ArrayNode add = (ArrayNode) mapper.readTree(rendered);
                c.setBodyTemplate(patchJson(c.getBodyTemplate(), obj -> appendFilters(obj, add)));
            }
            // U9 字符串 ge/le 过滤不可靠，改用「月前缀 like」展开：如 SM202611% 起到当月逐月一张
            if (spec.get("filtersAppendMonths") instanceof Map<?, ?> fm) {
                String field = String.valueOf(fm.get("Field"));
                String prefix = fm.get("Prefix") == null ? "" : String.valueOf(fm.get("Prefix"));
                String ym = TemplateRenderer.render(String.valueOf(fm.get("SinceYM")), vars).replace("-", "");
                String nowYm = new java.text.SimpleDateFormat("yyyyMM").format(new java.util.Date());
                ArrayNode add = mapper.createArrayNode();
                while (ym.length() == 6 && ym.compareTo(nowYm) <= 0) {
                    ObjectNode f = mapper.createObjectNode();
                    f.put("Field", field);
                    f.put("Operator", "like");
                    f.put("Value", prefix + ym + "%");
                    add.add(f);
                    int y = Integer.parseInt(ym.substring(0, 4));
                    int m = Integer.parseInt(ym.substring(4, 6)) + 1;
                    if (m > 12) { m = 1; y++; }
                    ym = String.format("%04d%02d", y, m);
                }
                c.setBodyTemplate(patchJson(c.getBodyTemplate(), obj -> appendFilters(obj, add)));
            }
            return c;
        } catch (Exception e) {
            log.warn("incremental filter apply failed: {}", e.getMessage());
            return ep;
        }
    }

    private void appendFilters(ObjectNode body, ArrayNode add) {
        ArrayNode filters = body.has("Filters") && body.get("Filters").isArray()
                ? (ArrayNode) body.get("Filters") : body.putArray("Filters");
        for (com.fasterxml.jackson.databind.JsonNode n : add) filters.add(n);
    }

    /** 模板里的 {{占位符}} 不是合法 JSON：先换成标记、改完再还原 */
    private String patchJson(String template, java.util.function.Consumer<ObjectNode> mutator) throws Exception {
        String src = (template == null || template.isBlank()) ? "{}" : template;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"\\{\\{\\s*[\\w.]+\\s*}}\"|\\{\\{\\s*[\\w.]+\\s*}}").matcher(src);
        List<String> restores = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String hit = m.group();
            String marker = "__PH" + restores.size() + "__";
            restores.add(hit);
            sb.append(src, last, m.start()).append('"').append(marker).append('"');
            last = m.end();
        }
        sb.append(src.substring(last));
        ObjectNode obj = (ObjectNode) mapper.readTree(sb.toString());
        mutator.accept(obj);
        String out = obj.toString();
        for (int i = 0; i < restores.size(); i++) {
            out = out.replace("\"__PH" + i + "__\"", restores.get(i));
        }
        return out;
    }

    private ApiEndpointDef copyOf(ApiEndpointDef ep) {
        ApiEndpointDef c = new ApiEndpointDef();
        c.setCode(ep.getCode());
        c.setDataSourceCode(ep.getDataSourceCode());
        c.setName(ep.getName());
        c.setMethod(ep.getMethod());
        c.setPath(ep.getPath());
        c.setQueryTemplate(ep.getQueryTemplate());
        c.setBodyTemplate(ep.getBodyTemplate());
        c.setBodyType(ep.getBodyType());
        c.setHeadersTemplate(ep.getHeadersTemplate());
        c.setListPath(ep.getListPath());
        c.setTotalPath(ep.getTotalPath());
        c.setPagination(ep.getPagination());
        return c;
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

    /** 尽力提取业务键，便于去重/排查/增量 upsert */
    static String extractKey(ObjectNode node) {
        String[] keys = {"id", "Id", "ID", "docNo", "DocNo", "m_docNo", "hangNo", "orderNo", "code", "barCode", "productionOrderNo"};
        for (String k : keys) {
            if (node.has(k) && !node.get(k).isNull()) {
                String v = node.get(k).asText("");
                if (!v.isBlank()) return v;
            }
        }
        // 行级单据：嵌套单号 + 行号（MiscRcvTrans.DocNo + DocLineNo 等）
        String doc = null;
        for (String cand : new String[] {"MiscRcvTrans", "Ship", "Doc", "Bill", "MiscRcv"}) {
            if (node.get(cand) instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
                for (String dk : new String[] {"DocNo", "docNo", "m_docNo"}) {
                    if (o.has(dk) && !o.get(dk).isNull() && !o.get(dk).asText("").isBlank()) {
                        doc = o.get(dk).asText("");
                        break;
                    }
                }
            }
            if (doc != null) break;
        }
        String line = null;
        for (String cand : new String[] {"DocLineNo", "LineNo", "lineNo", "m_docLineNo"}) {
            if (node.has(cand) && !node.get(cand).isNull()) {
                line = node.get(cand).asText("");
                if (!line.isBlank()) break;
            }
        }
        if (doc != null && line != null) return doc + "#" + line;
        // 兜底：内容哈希（同一内容稳定；上游改动会成为新行，靠全量同步清理）
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] h = md.digest(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("h:");
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
