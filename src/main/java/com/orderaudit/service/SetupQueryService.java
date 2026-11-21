package com.orderaudit.service;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLine;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLine;
import com.orderaudit.report.QuantityFormat;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.PackLineRepository;
import com.orderaudit.repository.RecipeLineRepository;
import com.orderaudit.repository.RecipeRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only: how each product is made and packed, in words a person can check against the recipe sheet. */
@Service
public class SetupQueryService {

    public record Ingredient(String name, String quantity) {
    }

    public record PackView(String product, int packSizeGrams, int packetsPerCarton, List<String> packaging) {
    }

    public record RecipeView(String name, String batchYield, String tolerance, List<Ingredient> ingredients,
                             List<PackView> packs) {
    }

    private final RecipeRepository recipes;
    private final RecipeLineRepository recipeLines;
    private final PackConfigRepository packConfigs;
    private final PackLineRepository packLines;
    private final ItemRepository items;

    public SetupQueryService(RecipeRepository recipes, RecipeLineRepository recipeLines,
                             PackConfigRepository packConfigs, PackLineRepository packLines, ItemRepository items) {
        this.recipes = recipes;
        this.recipeLines = recipeLines;
        this.packConfigs = packConfigs;
        this.packLines = packLines;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public List<RecipeView> all() {
        List<RecipeView> views = new ArrayList<>();
        for (Recipe r : recipes.findAll()) {
            List<Ingredient> ingredients = new ArrayList<>();
            for (RecipeLine l : recipeLines.findByRecipeId(r.getId())) {
                Item item = items.findById(l.getItemId()).orElseThrow();
                ingredients.add(new Ingredient(item.getName(), QuantityFormat.display(l.getQuantityBase(), item.getBaseUnit())));
            }
            List<PackView> packs = new ArrayList<>();
            for (PackConfig c : packConfigs.findAll()) {
                if (!c.getRecipeId().equals(r.getId())) {
                    continue;
                }
                List<String> packaging = new ArrayList<>();
                for (PackLine p : packLines.findByPackConfigId(c.getId())) {
                    Item item = items.findById(p.getItemId()).orElseThrow();
                    packaging.add(p.getQuantity() + " x " + item.getName() + " per "
                            + (p.getBasis().name().equals("PER_CARTON") ? "carton" : "packet"));
                }
                packs.add(new PackView(items.findById(c.getFinishedItemId()).orElseThrow().getName(),
                        c.getPackSizeGrams(), c.getPacketsPerCarton(), packaging));
            }
            views.add(new RecipeView(r.getName(), QuantityFormat.display(r.getBulkYieldGrams(),
                    com.orderaudit.domain.Unit.GRAM),
                    r.getTolerancePct() == null ? "default (3%)" : r.getTolerancePct().stripTrailingZeros().toPlainString() + "%",
                    ingredients, packs));
        }
        return views;
    }
}
