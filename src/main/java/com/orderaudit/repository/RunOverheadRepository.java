package com.orderaudit.repository;

import com.orderaudit.domain.RunOverhead;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunOverheadRepository extends JpaRepository<RunOverhead, Long> {
    List<RunOverhead> findByRunId(Long runId);
}
