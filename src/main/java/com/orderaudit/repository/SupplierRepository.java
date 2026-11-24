package com.orderaudit.repository;

import com.orderaudit.domain.Supplier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByGstin(String gstin);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    boolean existsByGstinAndIdNot(String gstin, Long id);
    List<Supplier> findAllByOrderByNameAsc();

    List<Supplier> findByActiveTrueOrderByNameAsc();
}
