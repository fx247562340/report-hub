package com.reporthub.repo;

import com.reporthub.domain.ApiEndpointDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApiEndpointRepo extends JpaRepository<ApiEndpointDef, String> {
    List<ApiEndpointDef> findByDataSourceCode(String dataSourceCode);
}
