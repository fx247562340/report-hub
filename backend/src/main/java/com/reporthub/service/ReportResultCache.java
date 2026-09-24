package com.reporthub.service;

import com.reporthub.config.ReportHubProperties;
import com.reporthub.engine.ReportQueryEngine;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按「报表 + 筛选条件」缓存完整结果集（关联、投影之后）。
 * 查询命中可切页；导出命中直接出 Excel，避免重复打上游。
 */
@Service
public class ReportResultCache {

    public record CachedResult(List<Map<String, Object>> columns,
                               List<Map<String, Object>> rows,
                               long total,
                               long cachedAtMs) {}

    private final ReportHubProperties props;
    private final Map<String, CachedResult> store = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedResult>> inflight = new ConcurrentHashMap<>();

    public ReportResultCache(ReportHubProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.getEngine().getResultCacheTtlSeconds() > 0;
    }

    public String key(String reportCode, Map<String, Object> filters) {
        return reportCode + ":" + sha256(canonical(filters));
    }

    public CachedResult getFresh(String key) {
        if (!enabled()) return null;
        CachedResult c = store.get(key);
        if (c == null) return null;
        long ttlMs = props.getEngine().getResultCacheTtlSeconds() * 1000L;
        if (System.currentTimeMillis() - c.cachedAtMs() > ttlMs) {
            store.remove(key);
            return null;
        }
        return c;
    }

    /** 命中则返回；未命中则同步加载并写入。并发同一 key 只打一次上游。 */
    public CachedResult getOrLoad(String key, Supplier<CachedResult> loader) {
        CachedResult hit = getFresh(key);
        if (hit != null) return hit;
        if (!enabled()) return loader.get();

        CompletableFuture<CachedResult> f = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        CachedResult again = getFresh(k);
                        if (again != null) return again;
                        CachedResult r = loader.get();
                        store.put(k, r);
                        return r;
                    } finally {
                        inflight.remove(k);
                    }
                })
        );
        try {
            return f.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    /**
     * 查询后预热全量缓存，导出可直接命中。
     * 延迟启动，避免与首屏分页查询抢上游连接。
     */
    public void warmAsync(String key, Supplier<CachedResult> loader) {
        if (!enabled() || !props.getEngine().isWarmOnQuery()) return;
        if (getFresh(key) != null || inflight.containsKey(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                getOrLoad(key, loader);
            } catch (Exception ignored) {
                // 预热失败不影响本次查询
            }
        });
    }

    public void invalidateReport(String reportCode) {
        String prefix = reportCode + ":";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void invalidateAll() {
        store.clear();
    }

    public ReportQueryEngine.QueryResult toQueryResult(CachedResult c, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = pageSize <= 0 ? 50 : pageSize;
        List<Map<String, Object>> rows = c.rows();
        List<Map<String, Object>> slice;
        if (size == Integer.MAX_VALUE) {
            slice = new ArrayList<>(rows);
        } else {
            int from = (p - 1) * size;
            if (from >= rows.size()) {
                slice = new ArrayList<>();
            } else {
                slice = new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + size)));
            }
        }
        List<ReportQueryEngine.TraceCall> trace = List.of(
                new ReportQueryEngine.TraceCall("cache", "result-cache", "hit", slice.size(), 0));
        return new ReportQueryEngine.QueryResult(c.columns(), slice, p, size, c.total(), trace, 0);
    }

    private static String canonical(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return "{}";
        return writeCanonical(filters);
    }

    @SuppressWarnings("unchecked")
    private static String writeCanonical(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, String> parts = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                parts.put(String.valueOf(e.getKey()), writeCanonical(e.getValue()));
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : parts.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStr(e.getKey())).append(':').append(e.getValue());
            }
            return sb.append('}').toString();
        }
        if (v instanceof Collection<?> col) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : col) {
                if (!first) sb.append(',');
                first = false;
                sb.append(writeCanonical(o));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return jsonStr(String.valueOf(v));
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
