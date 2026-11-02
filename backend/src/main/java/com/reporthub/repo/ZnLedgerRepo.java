package com.reporthub.repo;

import com.reporthub.domain.ZnLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZnLedgerRepo extends JpaRepository<ZnLedger, Long> {
    List<ZnLedger> findAllByOrderByWorkDateDesc();
    List<ZnLedger> findByWorkDateBetweenOrderByWorkDateDesc(LocalDate start, LocalDate end);
    Optional<ZnLedger> findByWorkDate(LocalDate workDate);
    boolean existsByWorkDateAndIdNot(LocalDate workDate, Long id);
    boolean existsByWorkDate(LocalDate workDate);
}
