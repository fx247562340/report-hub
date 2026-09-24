package com.reporthub.repo;

import com.reporthub.domain.DashboardWidget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardWidgetRepo extends JpaRepository<DashboardWidget, String> {
    List<DashboardWidget> findAllByOrderBySortAsc();
}
