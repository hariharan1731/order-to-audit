package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for item masters. Controllers will call this; it calls the repository.
 * Rules: name required and unique (ignoring case); base unit must suit the item type;
 * GST rate 0-100 with at most 2 decimals; HSN, if given, is 4, 6 or 8 digits.
 */
@Service
public class ItemService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ItemRepository repository;

    // Spring passes the repository in here (constructor injection).
    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    @Transactional // all-or-nothing: if anything throws, nothing is saved
    public Item create(String name, ItemType type, Unit baseUnit, String hsnCode, BigDecimal gstRatePercent) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new IllegalArgumentException("Item name is required and must be at most 120 characters");
        }
        if (type == null || baseUnit == null) {
            throw new IllegalArgumentException("Item type and base unit are required");
        }
        if (!type.allows(baseUnit)) {
            throw new IllegalArgumentException(
                    type + " items must use one of " + type.allowedBaseUnits() + " but got " + baseUnit);
        }
        if (gstRatePercent == null
                || gstRatePercent.signum() < 0
                || gstRatePercent.compareTo(HUNDRED) > 0
                || gstRatePercent.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("GST rate must be between 0 and 100 with at most 2 decimals");
        }
        String cleanHsn = (hsnCode == null || hsnCode.isBlank()) ? null : hsnCode.trim();
        if (cleanHsn != null && !cleanHsn.matches("\\d{4}|\\d{6}|\\d{8}")) {
            throw new IllegalArgumentException("HSN code must be 4, 6 or 8 digits");
        }
        if (repository.existsByNameIgnoreCase(cleanName)) {
            throw new DuplicateItemException(cleanName);
        }
        return repository.save(new Item(cleanName, type, baseUnit, cleanHsn, gstRatePercent));
    }

    @Transactional(readOnly = true)
    public List<Item> activeItemsOfType(ItemType type) {
        return repository.findByItemTypeAndActiveTrueOrderByNameAsc(type);
    }
}
