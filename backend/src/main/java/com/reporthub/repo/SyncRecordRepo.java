package com.reporthub.repo;

import com.reporthub.domain.SyncRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface SyncRecordRepo extends JpaRepository<SyncRecord, Long> {
    List<SyncRecord> findByDatasetCodeOrderByIdAsc(String datasetCode);
    long countByDatasetCode(String datasetCode);

    @Modifying
    @Query("delete from SyncRecord r where r.datasetCode = :ds")
    int deleteByDatasetCode(@Param("ds") String datasetCode);

    @Query("select max(r.syncedAt) from SyncRecord r where r.datasetCode = :ds")
    java.time.Instant findLastSyncedAt(@Param("ds") String datasetCode);

    @Query("select r.id, r.sourceKey from SyncRecord r where r.datasetCode = :ds and r.sourceKey is not null")
    List<Object[]> findIdKeyByDatasetCode(@Param("ds") String datasetCode);
}
