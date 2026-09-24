package com.reporthub.repo;

import com.reporthub.domain.FieldMappingDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldMappingRepo extends JpaRepository<FieldMappingDef, Long> {
    List<FieldMappingDef> findByDatasetCodeOrderBySortAsc(String datasetCode);
    void deleteByDatasetCode(String datasetCode);
}
