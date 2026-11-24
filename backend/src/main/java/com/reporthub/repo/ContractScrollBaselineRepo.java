package com.reporthub.repo;

import com.reporthub.domain.ContractScrollBaseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractScrollBaselineRepo extends JpaRepository<ContractScrollBaseline, Long> {
    List<ContractScrollBaseline> findAllByOrderBySortOrderAscIdAsc();
}
