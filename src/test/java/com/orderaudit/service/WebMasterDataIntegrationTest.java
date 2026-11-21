package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderaudit.domain.Customer;
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

/** Item master, the recipe/pack sheet and customer-specific prices, through the real stack. */
@SpringBootTest
@Testcontainers
@Transactional
class WebMasterDataIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    @Autowired ItemService itemService;
    @Autowired RecipeService recipeService;
    @Autowired PackConfigService packConfigService;
    @Autowired StockAdjustmentService adjustments;
    @Autowired SupplierService supplierService;
    @Autowired CustomerService customerService;
    @Autowired PriceService priceService;

    MockMvc mvc;
    BenchmarkFixture f;
    Customer retailerA;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = BenchmarkFixture.create("", null, 30, true, itemService, recipeService, packConfigService,
                adjustments, supplierService);
        retailerA = customerService.create("Retailer A", null, "33", null);
        priceService.setListPrice(f.murukku.getId(), 5_000);
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

    @Test
    void itemListShowsTheOfficialItems() throws Exception {
        String page = body(get("/admin/items").with(admin()));
        assertThat(page).contains("Flour").contains("Pouch 200 g").contains("Murukku 200 g").contains("GRAM")
                .contains("PACKET");
    }

    @Test
    void anItemCanBeAdded() throws Exception {
        String message = flashOf(post("/admin/items").with(admin()).with(csrf()).param("name", "Salt")
                .param("type", "RAW").param("baseUnit", "GRAM").param("gstRate", "0"), "message");
        assertThat(message).isEqualTo("Item added.");
        assertThat(body(get("/admin/items").with(admin()))).contains("Salt");
    }

    @Test
    void aDuplicateItemNameIsRefused() throws Exception {
        String error = flashOf(post("/admin/items").with(admin()).with(csrf()).param("name", "flour")
                .param("type", "RAW").param("baseUnit", "GRAM").param("gstRate", "0"), "error");
        assertThat(error).isNotBlank();
    }

    @Test
    void aWrongUnitForTheTypeIsRefused() throws Exception {
        String error = flashOf(post("/admin/items").with(admin()).with(csrf()).param("name", "Sugar")
                .param("type", "RAW").param("baseUnit", "PIECE").param("gstRate", "0"), "error");
        assertThat(error).contains("must use one of");
    }

    @Test
    void aBadGstRateIsRefused() throws Exception {
        String error = flashOf(post("/admin/items").with(admin()).with(csrf()).param("name", "Jaggery")
                .param("type", "RAW").param("baseUnit", "GRAM").param("gstRate", "150"), "error");
        assertThat(error).contains("GST rate");
    }

    @Test
    void aGstRateThatIsNotANumberIsRefused() throws Exception {
        String error = flashOf(post("/admin/items").with(admin()).with(csrf()).param("name", "Jaggery")
                .param("type", "RAW").param("baseUnit", "GRAM").param("gstRate", "five"), "error");
        assertThat(error).isNotBlank();
    }

    @Test
    void theRecipeSheetShowsTheOfficialBom() throws Exception {
        String page = body(get("/admin/setup").with(admin()));
        assertThat(page).contains("Murukku base").contains("40 kg").contains("10 L").contains("1.2 kg")
                .contains("200 g packets, 20 per carton").contains("1 x Pouch 200 g per packet")
                .contains("1 x Carton per carton");
    }

    @Test
    void aSpecialPriceIsSavedListedAndUsedForThatCustomerOnly() throws Exception {
        String message = flashOf(post("/admin/prices/special").with(admin()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("itemId", String.valueOf(f.murukku.getId())).param("priceRupees", "45"), "message");
        assertThat(message).isEqualTo("Special price saved.");
        assertThat(priceService.priceFor(retailerA.getId(), f.murukku.getId())).isEqualTo(4_500);
        Customer other = customerService.create("Distributor B", null, "29", null);
        assertThat(priceService.priceFor(other.getId(), f.murukku.getId())).isEqualTo(5_000);
        String page = body(get("/admin/prices").with(admin()));
        assertThat(page).contains("id=\"special-prices\"").contains("Rs 45.00").contains("Retailer A");
    }

    @Test
    void aNegativeSpecialPriceIsRefused() throws Exception {
        String error = flashOf(post("/admin/prices/special").with(admin()).with(csrf())
                .param("customerId", String.valueOf(retailerA.getId()))
                .param("itemId", String.valueOf(f.murukku.getId())).param("priceRupees", "-1"), "error");
        assertThat(error).isNotBlank();
        assertThat(priceService.priceFor(retailerA.getId(), f.murukku.getId())).isEqualTo(5_000);
    }

    @Test
    void masterDataScreensAreForOfficeStaffOnly() throws Exception {
        for (String path : new String[] {"/admin/items", "/admin/setup"}) {
            mvc.perform(get(path).with(user("ravi").roles("SALESMAN"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("prod").roles("PRODUCTION_SUPERVISOR"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("acc").roles("ACCOUNTANT"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/items").with(user("ravi").roles("SALESMAN")).with(csrf()).param("name", "X"))
                .andExpect(status().isForbidden());
    }
}
