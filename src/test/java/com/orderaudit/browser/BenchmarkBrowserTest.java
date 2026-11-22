package com.orderaudit.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real browser (Chromium via Playwright) walks the official benchmark through the real running app:
 * purchase, production run, order, approval, invoice, dispatch, payment, owner dashboard.
 * The steps depend on each other, so they run in a fixed order against one database seeded with the official data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.seed.enabled=true")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkBrowserTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String PASSWORD = "Demo@12345";

    @LocalServerPort int port;

    static Playwright playwright;
    static Browser browser;

    BrowserContext context;
    Page page;

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create();                // downloads Chromium the first time
        browser = playwright.chromium().launch();        // headless by default
    }

    @AfterAll
    static void stopBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void newContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Saves a picture for the README / demo; harmless if it cannot write. */
    private void shot(String name) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("docs/screenshots/" + name + ".png"))
                    .setFullPage(true));
        } catch (RuntimeException ignored) {
            // screenshots are a bonus, never a reason to fail a test
        }
    }

    private void loginAs(String username) {
        page.navigate(url("/login"));
        page.fill("#username", username);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        assertThat(page.locator("nav")).containsText(username);
    }

    @Test
    @Order(1)
    void wrongPasswordIsRejected() {
        page.navigate(url("/login"));
        page.fill("#username", "admin");
        page.fill("#password", "not-the-password1");
        page.click("button[type=submit]");
        assertThat(page).hasURL(Pattern.compile(".*/login\\?error.*"));
    }

    @Test
    @Order(2)
    void supervisorRecordsTheSixtyKgFlourPurchase() {
        loginAs("prod");
        page.navigate(url("/stock/purchases/new"));
        page.selectOption("#itemId-0", new com.microsoft.playwright.options.SelectOption().setLabel("Flour (kg)"));
        page.fill("#quantity-0", "60");
        page.fill("#rate-0", "45");
        page.fill("#reference", "BILL-1");
        page.click("#save-purchase");
        assertThat(page.locator("tr:has-text('Flour')")).containsText("160 kg");
    }

    @Test
    @Order(3)
    void supervisorPostsTheOfficialRunAndSeesTheFlags() {
        loginAs("prod");
        page.navigate(url("/production/new"));
        page.click("#show-plan");
        page.fill("tr:has-text('Oil') input[name=actual]", "10.5");
        page.fill("tr:has-text('Pouch') input[name=actual]", "194");
        page.fill("tr:has-text('Carton') input[name=actual]", "9");
        page.fill("#goodPackets", "188");
        page.fill("#damagedPouches", "6");
        page.fill("#labour", "1200");
        page.fill("#gasPower", "600");
        page.click("#post-run");
        assertThat(page.locator("#yield")).containsText("-6");
        assertThat(page.locator("#yield-flag")).isVisible();
        assertThat(page.locator("#cost-per-packet")).containsText("31.14");
        shot("production-run");
    }

    @Test
    @Order(4)
    void stockMatchesTheOfficialFigures() {
        loginAs("prod");
        page.navigate(url("/stock"));
        assertThat(page.locator("tr:has-text('Flour')")).containsText("120 kg");
        assertThat(page.locator("tr:has-text('Oil')")).containsText("19.5 L");
        assertThat(page.locator("tr:has-text('Pouch')")).containsText("306");
        assertThat(page.locator("tr:has-text('Murukku')")).containsText("238");
    }

    @Test
    @Order(5)
    void salesmanSendsRetailerAsOrderOfOneHundredFiftyPackets() {
        loginAs("ravi");
        page.navigate(url("/sales/orders/new"));
        page.selectOption("#customerId", new com.microsoft.playwright.options.SelectOption().setLabel("Retailer A"));
        page.fill("input.qty-input", "150");
        page.click("button[type=submit]:has-text('Send for approval')");
        page.navigate(url("/sales/orders"));
        assertThat(page.locator("body")).containsText("Retailer A");
    }

    @Test
    @Order(6)
    void adminApprovesInvoicesAndDispatches() {
        loginAs("admin");
        page.navigate(url("/admin/orders"));
        page.locator("a[href^='/admin/orders/']").first().click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Approve")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Raise invoice")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile("Dispatch.*"))).click();
        page.navigate(url("/admin/invoices"));
        assertThat(page.locator("body")).containsText("INV/2026-27/0001");
        shot("invoices");
        assertThat(page.locator("body")).containsText("7,875.00");
    }

    @Test
    @Order(7)
    void adminDownloadsTheInvoicePdf() {
        loginAs("admin");
        page.navigate(url("/admin/invoices"));
        page.locator("a[href^='/admin/invoices/']").first().click();
        String href = page.locator("#download-pdf").getAttribute("href");
        var response = context.request().get(url(href));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().get("content-type")).contains("pdf");
        assertThat(new String(response.body(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @Order(8)
    void adminRecordsAFourThousandRupeePayment() {
        loginAs("admin");
        page.navigate(url("/admin/payments"));
        page.locator("a:has-text('Record payment')").first().click();
        page.fill("input[name=amountRupees]", "4000");
        page.click("button[type=submit]:has-text('Record payment')");
        page.navigate(url("/admin/payments"));
        assertThat(page.locator("body")).containsText("3,875.00");
    }

    @Test
    @Order(9)
    void ownerDashboardShowsTheOfficialNumbers() {
        loginAs("owner");
        page.navigate(url("/owner"));
        assertThat(page.locator("#kpi-sales")).containsText("7,875.00");
        assertThat(page.locator("#kpi-outstanding")).containsText("3,875.00");
        assertThat(page.locator("#kpi-stock")).containsText("88");
        assertThat(page.locator("#kpi-flagged")).containsText("1");
        page.waitForTimeout(800);                          // let the charts finish drawing
        shot("owner-dashboard");
    }

    @Test
    @Order(10)
    void salesmanIsKeptOutOfProductionAndOwnerPages() {
        loginAs("ravi");
        var production = page.navigate(url("/production"));
        assertThat(production.status()).isEqualTo(403);
        var owner = page.navigate(url("/owner"));
        assertThat(owner.status()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void distributorBOrderOfOneHundredTwentyIsShortByThirtyTwo() {
        loginAs("ravi");
        page.navigate(url("/sales/orders/new"));
        page.selectOption("#customerId", new com.microsoft.playwright.options.SelectOption().setLabel("Distributor B"));
        page.fill("input.qty-input", "120");
        page.click("button[type=submit]:has-text('Send for approval')");
        context.clearCookies();

        loginAs("admin");
        page.navigate(url("/admin/orders"));
        page.locator("tr:has-text('Distributor B') a[href^='/admin/orders/']").first().click();
        assertThat(page.locator("body")).containsText("short 32");
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Approve"))).isDisabled();
        shot("distributor-b-shortage");
    }
}
