package com.orderaudit.service;

import com.orderaudit.domain.AdjustmentKind;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockAdjustment;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.repository.StockAdjustmentRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records opening stock and manual corrections. Creates the header document, then posts the movement. */
@Service
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustments;
    private final StockService stock;

    public StockAdjustmentService(StockAdjustmentRepository adjustments, StockService stock) {
        this.adjustments = adjustments;
        this.stock = stock;
    }

    /** Opening stock: quantity must be positive. unitCostPaise may be null (unvalued). */
    @Transactional
    public StockMovement recordOpening(long itemId, long quantityBase, BigDecimal unitCostPaise,
                                       String reason, String postedBy) {
        return record(AdjustmentKind.OPENING, MovementType.OPENING, itemId, quantityBase,
                unitCostPaise, reason, postedBy);
    }

    /** Manual correction in either direction (not zero). A reason is mandatory. */
    @Transactional
    public StockMovement recordCorrection(long itemId, long quantitySigned, BigDecimal unitCostPaise,
                                          String reason, String postedBy) {
        return record(AdjustmentKind.ADJUSTMENT, MovementType.ADJUST, itemId, quantitySigned,
                unitCostPaise, reason, postedBy);
    }

    private StockMovement record(AdjustmentKind kind, MovementType type, long itemId, long quantitySigned,
                                 BigDecimal unitCostPaise, String reason, String postedBy) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 300) {
            throw new IllegalArgumentException("A reason is required (at most 300 characters)");
        }
        if (!type.allows(quantitySigned)) {
            throw new IllegalArgumentException(type + " cannot have quantity " + quantitySigned);
        }
        StockAdjustment header = adjustments.save(new StockAdjustment(kind, cleanReason, postedBy));
        return stock.post(itemId, type, quantitySigned, unitCostPaise,
                SourceType.STOCK_ADJUSTMENT, header.getId(), postedBy);
    }
}
