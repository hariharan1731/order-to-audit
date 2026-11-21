package com.orderaudit.repository;

import com.orderaudit.domain.PackConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackConfigRepository extends JpaRepository<PackConfig, Long> {
    boolean existsByFinishedItemId(Long finishedItemId);
}
