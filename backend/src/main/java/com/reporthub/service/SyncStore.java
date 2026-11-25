package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.repo.SyncRecordRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取本地同步的上游原始行（JSONB），不做业务映射。
 * 使用 JdbcTemplate 直接读取 payload 并按 (count, max(synced_at)) 缓存已解析结果，
 * 避免每次本地查询重复把数十 MB JSONB 经 JPA 实体化并逐行 Jackson 反序列化。
 */
@Service
public class SyncStore {
    private final SyncRecordRepo repo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String, CachedRaw> cache = new ConcurrentHashMap<>();

    private record VersionStamp(long count, Instant maxSyncedAt) {
        String token() {
            return count + ":" + (maxSyncedAt == null ? 0L : maxSyncedAt.toEpochMilli());
        }
    }

    private record CachedRaw(VersionStamp version, List<ObjectNode> rows) {}

    public SyncStore(SyncRecordRepo repo, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<ObjectNode> loadRaw(String datasetCode) {
        VersionStamp ver = queryVersion(datasetCode);
        CachedRaw hit = cache.get(datasetCode);
        if (hit != null && hit.version().equals(ver)) {
            return hit.rows();
        }
        if (ver.count() == 0) {
            List<ObjectNode> empty = List.of();
            cache.put(datasetCode, new CachedRaw(ver, empty));
            return empty;
        }
        List<ObjectNode> out = new ArrayList<>((int) Math.min(ver.count(), 200_000));
        jdbc.query(
                "SELECT payload::text FROM sync_records WHERE dataset_code = ? ORDER BY id ASC",
                rs -> {
                    String json = rs.getString(1);
                    if (json != null && !json.isBlank()) {
                        try {
                            var node = mapper.readTree(json);
                            if (node instanceof ObjectNode obj) out.add(obj);
                        } catch (Exception ignored) {
                        }
                    }
                },
                datasetCode
        );
        List<ObjectNode> immutable = Collections.unmodifiableList(out);
        cache.put(datasetCode, new CachedRaw(ver, immutable));
        return immutable;
    }

    /** 返回数据集当前同步版本指纹（供上层投影缓存复用） */
    public String versionToken(String datasetCode) {
        return queryVersion(datasetCode).token();
    }

    public void invalidate(String datasetCode) {
        if (datasetCode != null) cache.remove(datasetCode);
    }

    public void invalidateAll() {
        cache.clear();
    }

    public Instant lastSyncedAt(String datasetCode) {
        return repo.findLastSyncedAt(datasetCode);
    }

    public long count(String datasetCode) {
        return repo.countByDatasetCode(datasetCode);
    }

    private VersionStamp queryVersion(String datasetCode) {
        return jdbc.queryForObject(
                "SELECT count(*), max(synced_at) FROM sync_records WHERE dataset_code = ?",
                (rs, rowNum) -> {
                    long cnt = rs.getLong(1);
                    Timestamp ts = rs.getTimestamp(2);
                    return new VersionStamp(cnt, ts == null ? null : ts.toInstant());
                },
                datasetCode
        );
    }
}
