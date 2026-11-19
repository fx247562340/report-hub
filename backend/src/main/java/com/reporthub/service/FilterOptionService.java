package com.reporthub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.domain.ReportDef;
import com.reporthub.engine.JsonPaths;
import com.reporthub.repo.FieldMappingRepo;
import com.reporthub.repo.SyncRecordRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 报表筛选下拉选项：根数据集本地行的投影列去重值（支持级联父过滤）。
 * Redis 缓存（key 含报告+字段+父值），每 6 小时过期由定时任务预冷；
 * Redis 不可用或未命中时直接扫本地 sync_records（慢路径，结果回填缓存）。
 */
@Service
public class FilterOptionService {
    private static final Logger log = LoggerFactory.getLogger(FilterOptionService.class);
    private static final Duration TTL = Duration.ofHours(6);
    private static final int MAX_SCAN = 20000;

    private final SyncRecordRepo syncRecordRepo;
    private final FieldMappingRepo fieldMappingRepo;
    private final ConfigService configService;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;

    public FilterOptionService(SyncRecordRepo syncRecordRepo, FieldMappingRepo fieldMappingRepo,
                               ConfigService configService, ObjectMapper mapper,
                               StringRedisTemplate redis) {
        this.syncRecordRepo = syncRecordRepo;
        this.fieldMappingRepo = fieldMappingRepo;
        this.configService = configService;
        this.mapper = mapper;
        this.redis = redis;
    }

    public record Options(List<String> values, boolean fromCache) {}

    /** key: filteropt:{report}:{field}:{parentField=parentValue 归一化} */
    private String cacheKey(String report, String field, String depends, String dependsValue) {
        String parent = depends == null ? "-" : depends;
        String pv = dependsValue == null || dependsValue.isBlank() ? "-" : dependsValue;
        return "filteropt:" + report + ":" + field + ":" + parent + "=" + pv;
    }

    public Options get(String reportCode, String field, String depends, String dependsValue) {
        ReportDef report = configService.getReport(reportCode);
        var mappings = fieldMappingRepo.findByDatasetCodeOrderBySortAsc(report.getRootDataset());
        java.util.function.Function<String, String> srcOf = (String key) -> {
            for (var m : mappings) {
                if (key.equals(m.getTargetField())) return m.getSourcePath();
            }
            return null;
        };
        String srcPath = srcOf.apply(field);
        if (srcPath == null || srcPath.isBlank()) return new Options(List.of(), false);
        String depSrcPath = null;
        Set<String> depValues = null;
        if (depends != null && !depends.isBlank() && dependsValue != null && !dependsValue.isBlank()) {
            depSrcPath = srcOf.apply(depends);
            depValues = new java.util.HashSet<>();
            for (String v : dependsValue.split(",")) {
                String t = v.trim();
                if (!t.isEmpty()) depValues.add(t);
            }
        }
        if (depValues != null && depSrcPath == null) return new Options(List.of(), false);

        String key = cacheKey(reportCode, field, depends, dependsValue);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return new Options(mapper.readValue(cached, new TypeReference<List<String>>() {}), true);
            }
        } catch (Exception e) {
            log.warn("filter options cache read failed: {}", e.getMessage());
        }

        List<String> values = scan(reportCode, report.getRootDataset(), srcPath, depSrcPath, depValues);
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(values), TTL);
        } catch (Exception e) {
            log.warn("filter options cache write failed: {}", e.getMessage());
        }
        return new Options(values, false);
    }

    private List<String> scan(String reportCode, String datasetCode, String srcPath,
                              String depSrcPath, Set<String> depValues) {
        Set<String> out = new TreeSet<>();
        int scanned = 0;
        for (var rec : syncRecordRepo.findByDatasetCodeOrderByIdAsc(datasetCode)) {
            try {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(rec.getPayload());
                if (depValues != null) {
                    String dep = textOf(JsonPaths.read(node, depSrcPath));
                    boolean hit = false;
                    for (String part : dep.split("[,，]")) {
                        if (depValues.contains(part.trim())) { hit = true; break; }
                    }
                    if (!hit) continue;
                }
                String v = textOf(JsonPaths.read(node, srcPath));
                if (v != null && !v.isBlank()) {
                    for (String part : v.split("[,，]")) {
                        String p = part.trim();
                        // 过滤测试值与序列化空值
                        if (p.isEmpty() || "null".equalsIgnoreCase(p) || "ceshi".equalsIgnoreCase(p)
                                || p.matches("\\d+")) continue;
                        out.add(p);
                    }
                }
                if (++scanned >= MAX_SCAN) break;
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(out);
    }

    /** 定时刷新全部已用选项缓存（由 SyncScheduler 每 30 分钟调一次）；低峰直接过期亦可 */
    public int refreshAll() {
        int n = 0;
        try {
            Set<String> keys = redis.keys("filteropt:*");
            if (keys == null || keys.isEmpty()) return 0;
            for (String key : keys) {
                // filteropt:{report}:{field}:{parent=pv}
                String[] parts = key.split(":");
                if (parts.length < 4) continue;
                String report = parts[1];
                String field = parts[2];
                String parentPart = parts[3];
                String depends = null, dependsValue = null;
                int eq = parentPart.indexOf('=');
                if (eq > 0) {
                    String parent = parentPart.substring(0, eq);
                    String pv = parentPart.substring(eq + 1);
                    if (!"-".equals(parent)) {
                        depends = parent;
                        dependsValue = "-".equals(pv) ? null : pv;
                    }
                }
                try {
                    var opts = get(report, field, depends, dependsValue);
                    n++;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("filter options refresh failed: {}", e.getMessage());
        }
        return n;
    }

    private static String textOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
