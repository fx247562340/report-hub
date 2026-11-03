package com.reporthub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.domain.SyncRecord;
import com.reporthub.repo.SyncRecordRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 读取本地同步的上游原始行（JSONB），不做业务映射 */
@Service
public class SyncStore {
    private final SyncRecordRepo repo;
    private final ObjectMapper mapper;

    public SyncStore(SyncRecordRepo repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public List<ObjectNode> loadRaw(String datasetCode) {
        List<SyncRecord> rows = repo.findByDatasetCodeOrderByIdAsc(datasetCode);
        List<ObjectNode> out = new ArrayList<>(rows.size());
        for (SyncRecord r : rows) {
            try {
                out.add((ObjectNode) mapper.readTree(r.getPayload()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public Instant lastSyncedAt(String datasetCode) {
        return repo.findLastSyncedAt(datasetCode);
    }

    public long count(String datasetCode) {
        return repo.countByDatasetCode(datasetCode);
    }
}
