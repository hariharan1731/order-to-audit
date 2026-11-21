package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.Purchase;
import com.orderaudit.domain.PurchaseLine;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.Supplier;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PurchaseLineRepository;
import com.orderaudit.repository.PurchaseRepository;
import com.orderaudit.repository.SupplierRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a purchase: the document plus one PURCHASE ledger row per line.
 * Rules: active supplier, at least one line, only RAW or PACKAGING items, positive quantity, cost zero or
 * more with at most 4 decimals, no item twice. Lines are posted in item-id order so two buyers
 * can never deadlock on each other's stock-row locks.
 */
@Service
public class PurchaseService {

    private final PurchaseRepository purchases;
    private final PurchaseLineRepository lines;
    private final SupplierRepository suppliers;
    private final ItemRepository items;
    private final StockService stock;

    public PurchaseService(PurchaseRepository purchases, PurchaseLineRepository lines,
                           SupplierRepository suppliers, ItemRepository items, StockService stock) {
        this.purchases = purchases;
        this.lines = lines;
        this.suppliers = suppliers;
        this.items = items;
        this.stock = stock;
    }

    @Transactional
    public Purchase record(long supplierId, LocalDate purchaseDate, String reference,
                           List<PurchaseLineSpec> specs, String postedBy) {
        if (purchaseDate == null) {
            throw new IllegalArgumentException("Purchase date is required");
        }
        if (reference != null && reference.length() > 60) {
            throw new IllegalArgumentException("Reference must be at most 60 characters");
        }
        if (postedBy == null || postedBy.isBlank()) {
            throw new IllegalArgumentException("postedBy is required");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("A purchase needs at least one line");
        }
        Supplier supplier = suppliers.findById(supplierId).orElseThrow(
                () -> new IllegalArgumentException("Supplier " + supplierId + " does not exist"));
        if (!supplier.isActive()) {
            throw new IllegalArgumentException("Supplier '" + supplier.getName() + "' is not active");
        }
        Set<Long> seen = new HashSet<>();
        for (PurchaseLineSpec spec : specs) {
            if (spec.quantityBase() <= 0) {
                throw new IllegalArgumentException("Quantities must be greater than zero");
            }
            if (spec.unitCostPaise() == null || spec.unitCostPaise().signum() < 0
                    || spec.unitCostPaise().stripTrailingZeros().scale() > 4) {
                throw new IllegalArgumentException("Cost must be zero or more with at most 4 decimals");
            }
            if (!seen.add(spec.itemId())) {
                throw new IllegalArgumentException("Item " + spec.itemId() + " appears twice in the purchase");
            }
            Item item = items.findById(spec.itemId()).orElseThrow(
                    () -> new IllegalArgumentException("Item " + spec.itemId() + " does not exist"));
            if (item.getItemType() == ItemType.FINISHED || !item.isActive()) {
                throw new IllegalArgumentException(
                        "'" + item.getName() + "' must be an active RAW or PACKAGING item to be purchased");
            }
        }

        Purchase purchase = purchases.save(new Purchase(supplierId, purchaseDate,
                reference == null || reference.isBlank() ? null : reference.trim(), postedBy.trim()));
        List<PurchaseLineSpec> ordered = specs.stream()
                .sorted(Comparator.comparingLong(PurchaseLineSpec::itemId)).toList();
        for (PurchaseLineSpec spec : ordered) {
            lines.save(new PurchaseLine(purchase.getId(), spec.itemId(), spec.quantityBase(), spec.unitCostPaise()));
            stock.post(spec.itemId(), MovementType.PURCHASE, spec.quantityBase(), spec.unitCostPaise(),
                    SourceType.PURCHASE, purchase.getId(), postedBy);
        }
        return purchase;
    }
}
