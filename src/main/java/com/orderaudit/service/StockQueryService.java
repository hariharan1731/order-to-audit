package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.report.QuantityFormat;
import com.orderaudit.repository.ItemRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: what is in the store right now, one row per item. */
@Service
public class StockQueryService {

    public record StockRow(long itemId, String name, ItemType type, String unit, long onHand, String readable) {
    }

    private final ItemRepository items;
    private final StockService stock;

    public StockQueryService(ItemRepository items, StockService stock) {
        this.items = items;
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public List<StockRow> snapshot() {
        return items.findAll().stream()
                .filter(Item::isActive)
                .sorted(Comparator.comparing((Item i) -> i.getItemType().ordinal()).thenComparing(Item::getName))
                .map(i -> {
                    long onHand = stock.balanceOf(i.getId());
                    return new StockRow(i.getId(), i.getName(), i.getItemType(),
                            QuantityFormat.baseUnitLabel(i.getBaseUnit()), onHand,
                            QuantityFormat.display(onHand, i.getBaseUnit()));
                }).toList();
    }

    /** Every active item, for drop-down lists. */
    @Transactional(readOnly = true)
    public List<Item> allItems() {
        return items.findAll().stream().filter(Item::isActive)
                .sorted(Comparator.comparing(Item::getName)).toList();
    }
}
