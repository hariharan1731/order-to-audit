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
    private final AuditService audit;

    public StockAdjustmentService(StockAdjustmentRepository adjustments, StockService stock, AuditService audit) {
        this.adjustments = adjustments;
        this.stock = stock;
        this.audit = audit;
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
        StockMovement movement = stock.post(itemId, type, quantitySigned, unitCostPaise,
                SourceType.STOCK_ADJUSTMENT, header.getId(), postedBy);
        // Stock is the most sensitive number in the business: every manual change is also in the audit log.
        audit.record(postedBy, kind == AdjustmentKind.OPENING ? "STOCK_OPENING_RECORDED" : "STOCK_ADJUSTED",
                "STOCK_ADJUSTMENT", header.getId(),
                "item " + itemId + ": " + (quantitySigned > 0 ? "+" : "") + quantitySigned + " base units. " + cleanReason);
        return movement;
    }
}
