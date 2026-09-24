package com.reporthub.repo;

import com.reporthub.domain.DatasetDef;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetRepo extends JpaRepository<DatasetDef, String> {
}
