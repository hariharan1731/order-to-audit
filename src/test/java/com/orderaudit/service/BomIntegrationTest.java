package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.BomPlan;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLine;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.RecipeLineRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL. Builds the official benchmark BOM and the 500 g interview scenario end to end. */
@SpringBootTest
@Testcontainers
@Transactional
class BomIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired BomService bomService;
    @Autowired PackConfigRepository packConfigRepository;
    @Autowired RecipeLineRepository recipeLineRepository;

    private Item flour, oil, spice, pouch200, carton, murukku200;
    private Recipe base;

    private void createOfficialItemsAndRecipe() {
        BigDecimal zero = BigDecimal.ZERO;
        flour = itemService.create("Flour", ItemType.RAW, Unit.GRAM, null, zero);
        oil = itemService.create("Oil", ItemType.RAW, Unit.MILLILITRE, null, zero);
        spice = itemService.create("Spice mix", ItemType.RAW, Unit.GRAM, null, zero);
        pouch200 = itemService.create("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, zero);
        carton = itemService.create("Carton", ItemType.PACKAGING, Unit.PIECE, null, zero);
        murukku200 = itemService.create("Murukku 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));

        // OFFICIAL per batch: flour 40 kg, oil 10 L, spice 1.2 kg. Bulk yield 200 packets x 200 g = 40,000 g.
        base = recipeService.create("Murukku base", 40_000, null, List.of(
                new RecipeLineSpec(flour.getId(), 40_000),
                new RecipeLineSpec(oil.getId(), 10_000),
                new RecipeLineSpec(spice.getId(), 1_200)));
    }

    @Test
    void officialBomIsStoredAndPlannedCorrectly() {
        createOfficialItemsAndRecipe();
        PackConfig config200 = packConfigService.create(murukku200.getId(), base.getId(), 200, 20, List.of(
                new PackLineSpec(pouch200.getId(), 1, PackBasis.PER_PACKET),
                new PackLineSpec(carton.getId(), 1, PackBasis.PER_CARTON)));

        BomPlan plan = bomService.plan(config200.getId(), BigDecimal.ONE);

        assertThat(plan.expectedPackets()).isEqualTo(200);
        assertThat(plan.quantityOf(flour.getId())).isEqualTo(40_000);
        assertThat(plan.quantityOf(oil.getId())).isEqualTo(10_000);
        assertThat(plan.quantityOf(spice.getId())).isEqualTo(1_200);
        assertThat(plan.quantityOf(pouch200.getId())).isEqualTo(200);
        assertThat(plan.quantityOf(carton.getId())).isEqualTo(10);
    }

    /** The interview change: a 500 g product shares the SAME recipe; only a new pack config is added. */
    @Test
    void aFiveHundredGramPackSharesTheSameRecipe() {
        createOfficialItemsAndRecipe();
        PackConfig config200 = packConfigService.create(murukku200.getId(), base.getId(), 200, 20, List.of(
                new PackLineSpec(pouch200.getId(), 1, PackBasis.PER_PACKET),
                new PackLineSpec(carton.getId(), 1, PackBasis.PER_CARTON)));

        Item murukku500 = itemService.create("Murukku 500 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        Item pouch500 = itemService.create("Pouch 500 g", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);
        Item carton500 = itemService.create("Carton 500 g", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);
        // 10 packets per carton is an illustration for this test, not an official figure.
        PackConfig config500 = packConfigService.create(murukku500.getId(), base.getId(), 500, 10, List.of(
                new PackLineSpec(pouch500.getId(), 1, PackBasis.PER_PACKET),
                new PackLineSpec(carton500.getId(), 1, PackBasis.PER_CARTON)));

        BomPlan plan500 = bomService.plan(config500.getId(), BigDecimal.ONE);

        assertThat(config500.getRecipeId()).isEqualTo(config200.getRecipeId());      // same recipe row
        assertThat(recipeLineRecipeCount()).isEqualTo(3);                              // still only 3 recipe lines
        assertThat(plan500.expectedPackets()).isEqualTo(80);
        assertThat(plan500.quantityOf(flour.getId())).isEqualTo(40_000);
        assertThat(plan500.quantityOf(pouch500.getId())).isEqualTo(80);
        assertThat(plan500.quantityOf(carton500.getId())).isEqualTo(8);
    }

    private long recipeLineRecipeCount() {
        return recipeLineRepository.findByRecipeId(base.getId()).size();
    }

    @Test
    void databaseAllowsOnlyOnePackConfigPerFinishedItem() {
        createOfficialItemsAndRecipe();
        packConfigRepository.saveAndFlush(new PackConfig(murukku200.getId(), base.getId(), 200, 20));

        assertThatThrownBy(() ->
                packConfigRepository.saveAndFlush(new PackConfig(murukku200.getId(), base.getId(), 500, 10)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsARecipeLineForAnItemThatDoesNotExist() {
        createOfficialItemsAndRecipe();

        assertThatThrownBy(() ->
                recipeLineRepository.saveAndFlush(new RecipeLine(base.getId(), 999_999L, 5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
