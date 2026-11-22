package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Editing items, switching them off, and creating recipes and pack configurations from the screens. */
@SpringBootTest
@Testcontainers
@Transactional
class WebSetupFormsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired AuditService audit;

    MockMvc mvc;
    BenchmarkFixture f;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
    }

    private static RequestPostProcessor admin() {
        return user("admin1").roles("ADMIN");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String flashOf(MockHttpServletRequestBuilder request, String key) throws Exception {
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        Object value = result.getFlashMap().get(key);
        return value == null ? null : value.toString();
    }

    private Item newRaw(String name) {
        return itemService.create(name, ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
    }

    // ---- item edit and switch off

    @Test
    void gstRateAndHsnCanBeChangedAndAreAudited() throws Exception {
        String message = flashOf(post("/admin/items/" + f.murukku.getId()).with(admin()).with(csrf())
                .param("hsnCode", "2106").param("gstRate", "12"), "message");
        assertThat(message).contains("Item updated");
        Item reloaded = itemService.all().stream().filter(i -> i.getId().equals(f.murukku.getId())).findFirst().orElseThrow();
        assertThat(reloaded.getGstRatePercent()).isEqualByComparingTo("12");
        assertThat(reloaded.getHsnCode()).isEqualTo("2106");
        assertThat(audit.forEntity("ITEM", f.murukku.getId())).extracting(AuditEntry::action).contains("ITEM_UPDATED");
    }

    @Test
    void aBadHsnCodeIsRefused() throws Exception {
        String error = flashOf(post("/admin/items/" + f.murukku.getId()).with(admin()).with(csrf())
                .param("hsnCode", "12").param("gstRate", "5"), "error");
        assertThat(error).contains("HSN");
    }

    @Test
    void anItemWithStockCannotBeSwitchedOff() throws Exception {
        String error = flashOf(post("/admin/items/" + f.flour.getId() + "/active").with(admin()).with(csrf())
                .param("active", "false"), "error");
        assertThat(error).contains("still has stock");
    }

    @Test
    void anItemUsedByARecipeCannotBeSwitchedOff() throws Exception {
        Item rice = newRaw("Rice flour");
        recipeService.create("Rice base", 30_000, null, List.of(new RecipeLineSpec(rice.getId(), 25_000)));
        String error = flashOf(post("/admin/items/" + rice.getId() + "/active").with(admin()).with(csrf())
                .param("active", "false"), "error");
        assertThat(error).contains("used by a recipe");
    }

    @Test
    void anUnusedItemCanBeSwitchedOffAndOn() throws Exception {
        Item salt = newRaw("Salt");
        assertThat(flashOf(post("/admin/items/" + salt.getId() + "/active").with(admin()).with(csrf())
                .param("active", "false"), "message")).isEqualTo("Item switched off.");
        assertThat(itemService.activeItemsOfType(ItemType.RAW)).extracting(Item::getName).doesNotContain("Salt");
        assertThat(body(get("/admin/items").with(admin()))).contains("Salt").contains("Inactive");
        assertThat(flashOf(post("/admin/items/" + salt.getId() + "/active").with(admin()).with(csrf())
                .param("active", "true"), "message")).isEqualTo("Item switched on.");
        assertThat(itemService.activeItemsOfType(ItemType.RAW)).extracting(Item::getName).contains("Salt");
    }

    // ---- recipes

    @Test
    void aRecipeCanBeCreatedFromTheScreen() throws Exception {
        Item rice = newRaw("Rice flour");
        String message = flashOf(post("/admin/setup/recipes").with(admin()).with(csrf())
                .param("name", "Rice murukku base").param("bulkYieldKg", "30").param("tolerancePct", "2.5")
                .param("itemId", String.valueOf(rice.getId())).param("quantity", "25")
                .param("itemId", "").param("quantity", ""), "message");
        assertThat(message).isEqualTo("Recipe saved.");
        String page = body(get("/admin/setup").with(admin()));
        assertThat(page).contains("Rice murukku base").contains("25 kg").contains("30 kg").contains("2.5%");
    }

    @Test
    void aDuplicateRecipeNameIsRefused() throws Exception {
        String error = flashOf(post("/admin/setup/recipes").with(admin()).with(csrf())
                .param("name", "murukku base").param("bulkYieldKg", "10")
                .param("itemId", String.valueOf(f.flour.getId())).param("quantity", "5"), "error");
        assertThat(error).isNotBlank();
    }

    @Test
    void aRecipeWithNoMaterialsIsRefused() throws Exception {
        String error = flashOf(post("/admin/setup/recipes").with(admin()).with(csrf())
                .param("name", "Empty").param("bulkYieldKg", "10"), "error");
        assertThat(error).contains("at least one raw material");
    }

    @Test
    void aGramFractionInARecipeIsRefused() throws Exception {
        String error = flashOf(post("/admin/setup/recipes").with(admin()).with(csrf())
                .param("name", "Odd").param("bulkYieldKg", "10")
                .param("itemId", String.valueOf(f.flour.getId())).param("quantity", "0.0004"), "error");
        assertThat(error).contains("too many decimals");
    }

    // ---- pack configurations

    @Test
    void aPackConfigurationCanBeCreatedFromTheScreen() throws Exception {
        Item rice = newRaw("Rice flour");
        Recipe recipe = recipeService.create("Rice base", 30_000, null, List.of(new RecipeLineSpec(rice.getId(), 25_000)));
        Item finished = itemService.create("Rice murukku 100 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
        String message = flashOf(post("/admin/setup/packs").with(admin()).with(csrf())
                .param("finishedItemId", String.valueOf(finished.getId())).param("recipeId", String.valueOf(recipe.getId()))
                .param("packSizeGrams", "100").param("packetsPerCarton", "30")
                .param("itemId", String.valueOf(f.pouch.getId())).param("quantity", "1").param("basis", "PER_PACKET")
                .param("itemId", String.valueOf(f.carton.getId())).param("quantity", "1").param("basis", "PER_CARTON"),
                "message");
        assertThat(message).isEqualTo("Pack configuration saved.");
        assertThat(body(get("/admin/setup").with(admin()))).contains("Rice murukku 100 g").contains("100 g packets, 30 per carton");
    }

    @Test
    void aSecondPackConfigurationForTheSameProductIsRefused() throws Exception {
        String error = flashOf(post("/admin/setup/packs").with(admin()).with(csrf())
                .param("finishedItemId", String.valueOf(f.murukku.getId())).param("recipeId", String.valueOf(f.recipe.getId()))
                .param("packSizeGrams", "200").param("packetsPerCarton", "20")
                .param("itemId", String.valueOf(f.pouch.getId())).param("quantity", "1").param("basis", "PER_PACKET"), "error");
        assertThat(error).isNotBlank();
    }

    @Test
    void aPackConfigurationNeedsPackaging() throws Exception {
        Item finished = itemService.create("Plain 50 g", ItemType.FINISHED, Unit.PACKET, null, BigDecimal.ZERO);
        String error = flashOf(post("/admin/setup/packs").with(admin()).with(csrf())
                .param("finishedItemId", String.valueOf(finished.getId())).param("recipeId", String.valueOf(f.recipe.getId()))
                .param("packSizeGrams", "50").param("packetsPerCarton", "10"), "error");
        assertThat(error).contains("at least one packaging");
    }

    @Test
    void creationsAreAudited() throws Exception {
        Item rice = newRaw("Rice flour");
        flashOf(post("/admin/setup/recipes").with(admin()).with(csrf()).param("name", "Rice base").param("bulkYieldKg", "30")
                .param("itemId", String.valueOf(rice.getId())).param("quantity", "25"), "message");
        assertThat(audit.recent(200)).extracting(AuditEntry::action).contains("RECIPE_CREATED");
    }

    @Test
    void onlyOfficeStaffMayPostSetupForms() throws Exception {
        mvc.perform(post("/admin/setup/recipes").with(user("ravi").roles("SALESMAN")).with(csrf()).param("name", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/items/" + f.flour.getId() + "/active").with(user("prod").roles("PRODUCTION_SUPERVISOR"))
                .with(csrf()).param("active", "false")).andExpect(status().isForbidden());
    }
}
