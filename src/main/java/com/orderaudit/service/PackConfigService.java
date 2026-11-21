package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLine;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.PackLineRepository;
import com.orderaudit.repository.RecipeRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules for a pack configuration: the finished item must be an active FINISHED item with no config yet,
 * the recipe must exist and be active, sizes positive, every packaging line a positive quantity of
 * an active PACKAGING item, no item twice.
 */
@Service
public class PackConfigService {

    private final PackConfigRepository configs;
    private final PackLineRepository lines;
    private final RecipeRepository recipes;
    private final ItemRepository items;

    public PackConfigService(PackConfigRepository configs, PackLineRepository lines,
                             RecipeRepository recipes, ItemRepository items) {
        this.configs = configs;
        this.lines = lines;
        this.recipes = recipes;
        this.items = items;
    }

    @Transactional
    public PackConfig create(long finishedItemId, long recipeId, int packSizeGrams, int packetsPerCarton,
                             List<PackLineSpec> specs) {
        if (packSizeGrams <= 0 || packetsPerCarton <= 0) {
            throw new IllegalArgumentException("Pack size and packets per carton must be greater than zero");
        }
        Item finished = items.findById(finishedItemId).orElseThrow(
                () -> new IllegalArgumentException("Item " + finishedItemId + " does not exist"));
        if (finished.getItemType() != ItemType.FINISHED || !finished.isActive()) {
            throw new IllegalArgumentException("'" + finished.getName() + "' must be an active FINISHED item");
        }
        Recipe recipe = recipes.findById(recipeId).orElseThrow(
                () -> new IllegalArgumentException("Recipe " + recipeId + " does not exist"));
        if (!recipe.isActive()) {
            throw new IllegalArgumentException("Recipe '" + recipe.getName() + "' is not active");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("A pack configuration needs at least one packaging line");
        }
        Set<Long> seen = new HashSet<>();
        for (PackLineSpec spec : specs) {
            if (spec.quantity() <= 0 || spec.basis() == null) {
                throw new IllegalArgumentException("Packaging lines need a positive quantity and a basis");
            }
            if (!seen.add(spec.itemId())) {
                throw new IllegalArgumentException("Item " + spec.itemId() + " appears twice in the packaging");
            }
            Item item = items.findById(spec.itemId()).orElseThrow(
                    () -> new IllegalArgumentException("Item " + spec.itemId() + " does not exist"));
            if (item.getItemType() != ItemType.PACKAGING || !item.isActive()) {
                throw new IllegalArgumentException("'" + item.getName() + "' must be an active PACKAGING item");
            }
        }
        if (configs.existsByFinishedItemId(finishedItemId)) {
            throw new DuplicateRecordException("Pack configuration for item", finished.getName());
        }
        PackConfig saved = configs.save(new PackConfig(finishedItemId, recipeId, packSizeGrams, packetsPerCarton));
        for (PackLineSpec spec : specs) {
            lines.save(new PackLine(saved.getId(), spec.itemId(), spec.quantity(), spec.basis()));
        }
        return saved;
    }
}
