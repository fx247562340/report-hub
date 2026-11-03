package com.reporthub.repo;

import com.reporthub.domain.SyncConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyncConfigRepo extends JpaRepository<SyncConfig, String> {
    List<SyncConfig> findByEnabledTrue();
}
