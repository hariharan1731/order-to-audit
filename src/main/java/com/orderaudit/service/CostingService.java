package com.orderaudit.service;

import com.orderaudit.domain.CostMovement;
import com.orderaudit.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weighted average cost of an item: replays its ledger (see WeightedAverageCost). */
@Service
public class CostingService {

    private final StockMovementRepository movements;

    public CostingService(StockMovementRepository movements) {
        this.movements = movements;
    }

    /** Paise per base unit, or null if the item has never been received with a cost. */
    @Transactional(readOnly = true)
    public BigDecimal weightedAverageCostPaise(long itemId) {
        List<CostMovement> history = movements.findByItemIdOrderByIdAsc(itemId).stream()
                .map(m -> new CostMovement(m.getId(), m.getQtySigned(), m.getUnitCost(), m.getReversesId()))
                .toList();
        return WeightedAverageCost.replay(history);
    }
}
