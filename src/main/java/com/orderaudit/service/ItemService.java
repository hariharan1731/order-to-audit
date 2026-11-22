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
    private final AuditService audit;
    private final StockService stock;
    private final com.orderaudit.repository.RecipeLineRepository recipeLines;
    private final com.orderaudit.repository.PackLineRepository packLines;
    private final com.orderaudit.repository.PackConfigRepository packConfigs;

    // Spring passes the repository in here (constructor injection).
    public ItemService(ItemRepository repository, AuditService audit, StockService stock,
                       com.orderaudit.repository.RecipeLineRepository recipeLines,
                       com.orderaudit.repository.PackLineRepository packLines,
                       com.orderaudit.repository.PackConfigRepository packConfigs) {
        this.repository = repository;
        this.audit = audit;
        this.stock = stock;
        this.recipeLines = recipeLines;
        this.packLines = packLines;
        this.packConfigs = packConfigs;
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
        Item saved = repository.save(new Item(cleanName, type, baseUnit, cleanHsn, gstRatePercent));
        audit.record(com.orderaudit.security.CurrentUser.name(), "ITEM_CREATED", "ITEM", saved.getId(),
                cleanName + " (" + type + ", " + baseUnit + ", GST " + gstRatePercent.stripTrailingZeros().toPlainString() + "%)");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Item> activeItemsOfType(ItemType type) {
        return repository.findByItemTypeAndActiveTrueOrderByNameAsc(type);
    }

    /** Every item, active or not, for the item master screen. */
    @Transactional(readOnly = true)
    public List<Item> all() {
        return repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Item::getName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Change HSN code and GST rate. Invoices already raised keep the rate they were issued with. */
    @Transactional
    public Item updateTaxDetails(long itemId, String hsnCode, BigDecimal gstRatePercent) {
        Item item = repository.findById(itemId).orElseThrow(
                () -> new IllegalArgumentException("Item " + itemId + " does not exist"));
        if (gstRatePercent == null || gstRatePercent.signum() < 0 || gstRatePercent.compareTo(HUNDRED) > 0
                || gstRatePercent.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("GST rate must be between 0 and 100 with at most 2 decimals");
        }
        String cleanHsn = (hsnCode == null || hsnCode.isBlank()) ? null : hsnCode.trim();
        if (cleanHsn != null && !cleanHsn.matches("\\d{4}|\\d{6}|\\d{8}")) {
            throw new IllegalArgumentException("HSN code must be 4, 6 or 8 digits");
        }
        String before = item.getHsnCode() + " / " + item.getGstRatePercent().stripTrailingZeros().toPlainString() + "%";
        item.changeTaxDetails(cleanHsn, gstRatePercent);
        audit.record(com.orderaudit.security.CurrentUser.name(), "ITEM_UPDATED", "ITEM", itemId,
                item.getName() + ": " + before + " -> " + cleanHsn + " / "
                        + gstRatePercent.stripTrailingZeros().toPlainString() + "%");
        return item;
    }

    /**
     * Switch an item off (hidden from drop-downs) or back on. An item cannot be switched off while stock remains or
     * while a recipe or pack configuration still uses it, because production would then break.
     */
    @Transactional
    public Item setActive(long itemId, boolean active) {
        Item item = repository.findById(itemId).orElseThrow(
                () -> new IllegalArgumentException("Item " + itemId + " does not exist"));
        if (!active) {
            if (stock.balanceOf(itemId) != 0) {
                throw new IllegalStateException("'" + item.getName() + "' still has stock; it cannot be switched off");
            }
            if (recipeLines.existsByItemId(itemId) || packLines.existsByItemId(itemId)
                    || packConfigs.existsByFinishedItemId(itemId)) {
                throw new IllegalStateException("'" + item.getName() + "' is used by a recipe or pack configuration");
            }
        }
        item.setActive(active);
        audit.record(com.orderaudit.security.CurrentUser.name(), active ? "ITEM_ACTIVATED" : "ITEM_DEACTIVATED",
                "ITEM", itemId, item.getName());
        return item;
    }
}
