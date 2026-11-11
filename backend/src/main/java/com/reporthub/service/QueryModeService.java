package com.reporthub.service;

import com.reporthub.domain.AppSetting;
import com.reporthub.repo.AppSettingRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** 查询模式：realtime=打上游；local=读本地 sync_records */
@Service
public class QueryModeService {
    public static final String KEY = "query_mode";
    public static final String REALTIME = "realtime";
    public static final String LOCAL = "local";

    private final AppSettingRepo repo;
    private volatile String cached;

    public QueryModeService(AppSettingRepo repo) {
        this.repo = repo;
    }

    public boolean isLocal() {
        return LOCAL.equals(mode());
    }

    public String mode() {
        String v = cached;
        if (v != null) return v;
        synchronized (this) {
            if (cached != null) return cached;
            v = repo.findById(KEY).map(AppSetting::getValue).orElse(LOCAL);
            cached = v;
            return v;
        }
    }

    public String setMode(String mode) {
        if (!REALTIME.equals(mode) && !LOCAL.equals(mode)) {
            throw new IllegalArgumentException("query_mode must be realtime|local");
        }
        AppSetting s = repo.findById(KEY).orElseGet(AppSetting::new);
        s.setKey(KEY);
        s.setValue(mode);
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        cached = mode;
        return mode;
    }

    public Map<String, Object> snapshot() {
        return Map.of("queryMode", mode());
    }
}
