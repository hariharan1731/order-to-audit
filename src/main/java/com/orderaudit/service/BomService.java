package com.orderaudit.service;

import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.PackLineRepository;
import com.orderaudit.repository.RecipeLineRepository;
import com.orderaudit.repository.RecipeRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads a pack config with its recipe from the database and asks BomPlanner for the planned quantities. */
@Service
public class BomService {

    private final PackConfigRepository configs;
    private final PackLineRepository packLines;
    private final RecipeRepository recipes;
    private final RecipeLineRepository recipeLines;

    public BomService(PackConfigRepository configs, PackLineRepository packLines,
                      RecipeRepository recipes, RecipeLineRepository recipeLines) {
        this.configs = configs;
        this.packLines = packLines;
        this.recipes = recipes;
        this.recipeLines = recipeLines;
    }

    @Transactional(readOnly = true)
    public BomPlan plan(long packConfigId, BigDecimal batchMultiplier) {
        PackConfig config = configs.findById(packConfigId).orElseThrow(
                () -> new IllegalArgumentException("Pack configuration " + packConfigId + " does not exist"));
        Recipe recipe = recipes.findById(config.getRecipeId()).orElseThrow(
                () -> new IllegalStateException("Recipe " + config.getRecipeId() + " is missing"));
        List<RecipeLineSpec> recipeSpecs = recipeLines.findByRecipeId(recipe.getId()).stream()
                .map(line -> new RecipeLineSpec(line.getItemId(), line.getQuantityBase()))
                .toList();
        List<PackLineSpec> packSpecs = packLines.findByPackConfigId(config.getId()).stream()
                .map(line -> new PackLineSpec(line.getItemId(), line.getQuantity(), line.getBasis()))
                .toList();
        return BomPlanner.plan(recipe.getBulkYieldGrams(), config.getPackSizeGrams(),
                config.getPacketsPerCarton(), recipeSpecs, packSpecs, batchMultiplier);
    }

    /** The packaging lines of a pack configuration (used to cross-check pouch counts against packets). */
    @Transactional(readOnly = true)
    public List<com.orderaudit.domain.PackLine> packLinesOf(long packConfigId) {
        return packLines.findByPackConfigId(packConfigId);
    }
}
