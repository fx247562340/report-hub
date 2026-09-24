package com.reporthub.repo;

import com.reporthub.domain.RelationDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationRepo extends JpaRepository<RelationDef, String> {
    List<RelationDef> findByReportCodeOrderBySortAsc(String reportCode);
    void deleteByReportCode(String reportCode);
}
