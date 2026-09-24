package com.reporthub.repo;

import com.reporthub.domain.DataSourceDef;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepo extends JpaRepository<DataSourceDef, String> {
}
