package com.orderaudit.repository;

import com.orderaudit.domain.StockBalance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {

    /** SELECT ... FOR UPDATE: other transactions wait here until this one finishes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.itemId = :itemId")
    Optional<StockBalance> findForUpdate(@Param("itemId") Long itemId);

    /** Creates the zero row on first use. Safe if two transactions try at the same time. */
    @Modifying
    @Query(value = "INSERT INTO stock_balance (item_id, qty_base) VALUES (:itemId, 0) ON CONFLICT (item_id) DO NOTHING",
            nativeQuery = true)
    void ensureRow(@Param("itemId") Long itemId);
}
