package com.orderaudit.repository;

import com.orderaudit.domain.RunLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunLineRepository extends JpaRepository<RunLine, Long> {
    List<RunLine> findByRunIdOrderByItemIdAsc(Long runId);
}
