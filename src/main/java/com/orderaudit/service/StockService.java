package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockBalance;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.StockBalanceRepository;
import com.orderaudit.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE choke point: nothing else in the application writes to the stock ledger.
 * Every posting runs in one transaction and follows the same steps:
 *   1. validate the request   2. lock the item's balance row   3. refuse if stock would go negative
 *   4. update the cached balance   5. append the ledger row.
 * If anything fails, the transaction rolls back and nothing is saved.
 */
@Service
public class StockService {

    private final StockMovementRepository movements;
    private final StockBalanceRepository balances;
    private final ItemRepository items;

    public StockService(StockMovementRepository movements, StockBalanceRepository balances, ItemRepository items) {
        this.movements = movements;
        this.balances = balances;
        this.items = items;
    }

    /** Post one stock movement. quantitySigned is in base units: positive in, negative out. */
    @Transactional
    public StockMovement post(long itemId, MovementType type, long quantitySigned, BigDecimal unitCostPaise,
                              SourceType sourceType, long sourceId, String postedBy) {
        if (type == MovementType.REVERSAL) {
            throw new IllegalArgumentException("Use reverse() to post a reversal");
        }
        return append(itemId, type, quantitySigned, unitCostPaise, sourceType, sourceId, null, postedBy);
    }

    /** Cancel an earlier movement by posting its exact opposite. The original row is never changed. */
    @Transactional
    public StockMovement reverse(long movementId, String postedBy) {
        StockMovement original = movements.findById(movementId).orElseThrow(
                () -> new IllegalArgumentException("Movement " + movementId + " does not exist"));
        if (original.getMovementType() == MovementType.REVERSAL) {
            throw new IllegalArgumentException("A reversal cannot be reversed");
        }
        if (movements.existsByReversesId(movementId)) {
            throw new IllegalStateException("Movement " + movementId + " has already been reversed");
        }
        return append(original.getItemId(), MovementType.REVERSAL, -original.getQtySigned(),
                original.getUnitCost(), original.getSourceType(), original.getSourceId(), movementId, postedBy);
    }

    private StockMovement append(long itemId, MovementType type, long quantitySigned, BigDecimal unitCost,
                                 SourceType sourceType, long sourceId, Long reversesId, String postedBy) {
        // 1. validate
        if (!type.allows(quantitySigned)) {
            throw new IllegalArgumentException(type + " cannot have quantity " + quantitySigned);
        }
        if (unitCost != null && (unitCost.signum() < 0 || unitCost.stripTrailingZeros().scale() > 4)) {
            throw new IllegalArgumentException("Unit cost must be zero or more, with at most 4 decimals");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("Every movement needs a source document");
        }
        if (postedBy == null || postedBy.isBlank() || postedBy.length() > 100) {
            throw new IllegalArgumentException("postedBy is required (at most 100 characters)");
        }
        if (type != MovementType.REVERSAL) {
            Item item = items.findById(itemId).orElseThrow(
                    () -> new IllegalArgumentException("Item " + itemId + " does not exist"));
            if (!item.isActive()) {
                throw new IllegalArgumentException("Item '" + item.getName() + "' is not active");
            }
        }

        // 2. lock the balance row (create it first if this is the item's first movement)
        balances.ensureRow(itemId);
        StockBalance balance = balances.findForUpdate(itemId).orElseThrow(
                () -> new IllegalStateException("Balance row missing for item " + itemId));

        // 3. never allow negative stock
        if (balance.getQtyBase() + quantitySigned < 0) {
            throw new InsufficientStockException(itemId, balance.getQtyBase(), -quantitySigned);
        }

        // 4 + 5. update the cache and append the ledger row in the same transaction
        balance.apply(quantitySigned);
        return movements.save(new StockMovement(itemId, type, quantitySigned, unitCost,
                sourceType, sourceId, reversesId, postedBy.trim()));
    }

    /**
     * Take the row lock for an item NOW and keep it until the transaction ends. Used by production
     * so that the cost is calculated while nobody else can change this item's stock.
     */
    @Transactional
    public void lock(long itemId) {
        balances.ensureRow(itemId);
        balances.findForUpdate(itemId);
    }

    /** Cached balance in base units (0 if the item has never moved). */
    @Transactional(readOnly = true)
    public long balanceOf(long itemId) {
        return balances.findById(itemId).map(StockBalance::getQtyBase).orElse(0L);
    }

    /** The real stock: the sum of the ledger. */
    @Transactional(readOnly = true)
    public long ledgerBalanceOf(long itemId) {
        return movements.ledgerSum(itemId);
    }

    /** Items whose cache differs from the ledger. An empty list proves the cache is correct. */
    @Transactional(readOnly = true)
    public List<Long> reconcile() {
        return movements.findItemIdsWhereBalanceDiffersFromLedger();
    }

    @Transactional(readOnly = true)
    public List<StockMovement> ledgerFor(long itemId) {
        return movements.findByItemIdOrderByIdAsc(itemId);
    }
}
