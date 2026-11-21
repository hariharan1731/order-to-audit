package com.orderaudit.repository;

import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockMovement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    boolean existsByReversesId(Long reversesId);

    List<StockMovement> findByItemIdOrderByIdAsc(Long itemId);

    /** Every movement caused by one document, e.g. all rows of production run 7. */
    List<StockMovement> findBySourceTypeAndSourceIdOrderByIdAsc(SourceType sourceType, Long sourceId);

    /** The ledger total for one item: the real stock. */
    @Query("select coalesce(sum(m.qtySigned), 0L) from StockMovement m where m.itemId = :itemId")
    long ledgerSum(@Param("itemId") Long itemId);

    /** Items whose cached balance differs from the ledger total. Should always be empty. */
    @Query(value = """
            SELECT i.id
            FROM item i
            LEFT JOIN stock_balance b ON b.item_id = i.id
            WHERE COALESCE(b.qty_base, 0)
                  <> COALESCE((SELECT SUM(m.qty_signed) FROM stock_movement m WHERE m.item_id = i.id), 0)
            ORDER BY i.id
            """, nativeQuery = true)
    List<Long> findItemIdsWhereBalanceDiffersFromLedger();
}
