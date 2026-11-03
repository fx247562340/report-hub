package com.reporthub.repo;

import com.reporthub.domain.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyncJobRepo extends JpaRepository<SyncJob, Long> {
    List<SyncJob> findTop50ByOrderByStartedAtDesc();
    List<SyncJob> findByDatasetCodeOrderByStartedAtDesc(String datasetCode);
}
