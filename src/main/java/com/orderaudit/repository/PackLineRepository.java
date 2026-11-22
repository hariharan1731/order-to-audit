package com.orderaudit.repository;

import com.orderaudit.domain.PackLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackLineRepository extends JpaRepository<PackLine, Long> {
    List<PackLine> findByPackConfigId(Long packConfigId);

    boolean existsByItemId(Long itemId);
}
