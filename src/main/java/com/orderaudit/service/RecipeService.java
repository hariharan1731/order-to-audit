package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLine;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.RecipeLineRepository;
import com.orderaudit.repository.RecipeRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules for creating a shared recipe: unique name, positive bulk yield, optional tolerance 0-100,
 * at least one line, every line a positive quantity of an ACTIVE RAW item, no item twice.
 */
@Service
public class RecipeService {

    private final RecipeRepository recipes;
    private final RecipeLineRepository lines;
    private final ItemRepository items;
    private final AuditService audit;

    public RecipeService(RecipeRepository recipes, RecipeLineRepository lines, ItemRepository items,
                         AuditService audit) {
        this.audit = audit;
        this.recipes = recipes;
        this.lines = lines;
        this.items = items;
    }

    @Transactional
    public Recipe create(String name, long bulkYieldGrams, BigDecimal tolerancePct, List<RecipeLineSpec> specs) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 120) {
            throw new IllegalArgumentException("Recipe name is required and must be at most 120 characters");
        }
        if (bulkYieldGrams <= 0) {
            throw new IllegalArgumentException("Bulk yield must be greater than zero grams");
        }
        if (tolerancePct != null && (tolerancePct.signum() < 0
                || tolerancePct.compareTo(BigDecimal.valueOf(100)) > 0
                || tolerancePct.stripTrailingZeros().scale() > 2)) {
            throw new IllegalArgumentException("Tolerance must be between 0 and 100 with at most 2 decimals");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("A recipe needs at least one raw material");
        }
        Set<Long> seen = new HashSet<>();
        for (RecipeLineSpec spec : specs) {
            if (spec.quantityBase() <= 0) {
                throw new IllegalArgumentException("Quantities must be greater than zero");
            }
            if (!seen.add(spec.itemId())) {
                throw new IllegalArgumentException("Item " + spec.itemId() + " appears twice in the recipe");
            }
            Item item = items.findById(spec.itemId()).orElseThrow(
                    () -> new IllegalArgumentException("Item " + spec.itemId() + " does not exist"));
            if (item.getItemType() != ItemType.RAW || !item.isActive()) {
                throw new IllegalArgumentException("'" + item.getName() + "' must be an active RAW item");
            }
        }
        if (recipes.existsByNameIgnoreCase(cleanName)) {
            throw new DuplicateRecordException("Recipe", cleanName);
        }
        Recipe saved = recipes.save(new Recipe(cleanName, bulkYieldGrams, tolerancePct));
        for (RecipeLineSpec spec : specs) {
            lines.save(new RecipeLine(saved.getId(), spec.itemId(), spec.quantityBase()));
        }
        audit.record(com.orderaudit.security.CurrentUser.name(), "RECIPE_CREATED", "RECIPE", saved.getId(),
                cleanName + " (" + specs.size() + " materials, bulk " + bulkYieldGrams + " g)");
        return saved;
    }
}
