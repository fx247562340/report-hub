package com.reporthub.repo;

import com.reporthub.domain.ReportDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepo extends JpaRepository<ReportDef, String> {
    List<ReportDef> findAllByOrderByUpdatedAtDesc();
}
