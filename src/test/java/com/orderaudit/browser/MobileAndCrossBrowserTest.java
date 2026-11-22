package com.orderaudit.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The salesman's phone screen on a 375 x 667 touch device, and a login smoke test in all three browser engines. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.seed.enabled=true")
@Testcontainers
class MobileAndCrossBrowserTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String PASSWORD = "Demo@12345";

    @LocalServerPort int port;

    static Playwright playwright;

    @BeforeAll
    static void start() {
        playwright = Playwright.create();
    }

    @AfterAll
    static void stop() {
        playwright.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private BrowserType engine(String name) {
        return switch (name) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
    }

    private void login(Page page, String user) {
        page.navigate(url("/login"));
        page.fill("#username", user);
        page.fill("#password", PASSWORD);
        page.click("button[type=submit]");
        // wait until the login has finished (we have left /login) so the next navigation cannot cancel it
        page.waitForURL(u -> !u.contains("/login"));
    }

    @Test
    void salesmanOrderScreenFitsAPhoneAndHasBigTapTargets() {
        try (Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(375, 667).setIsMobile(true).setHasTouch(true))) {
            Page page = context.newPage();
            login(page, "ravi");
            page.navigate(url("/sales/orders/new"));

            // nothing may be wider than the screen (no sideways scrolling)
            Object overflow = page.evaluate("document.documentElement.scrollWidth - window.innerWidth");
            assertThat(((Number) overflow).intValue()).isLessThanOrEqualTo(1);

            // the quantity box and the send button are comfortably tappable (>= 44 px high)
            double qtyHeight = page.locator("input.qty-input").first().boundingBox().height;
            double buttonHeight = page.locator("button:has-text('Send for approval')").boundingBox().height;
            assertThat(qtyHeight).isGreaterThanOrEqualTo(44.0);
            assertThat(buttonHeight).isGreaterThanOrEqualTo(44.0);

            // the menu collapses behind the hamburger button on a phone
            assertThat(page.locator(".navbar-toggler")).isVisible();
        }
    }

    @Test
    void aSalesmanCanPlaceAnOrderOnAPhone() {
        try (Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(375, 667).setIsMobile(true).setHasTouch(true))) {
            Page page = context.newPage();
            login(page, "ravi");
            page.navigate(url("/sales/orders/new"));
            page.selectOption("#customerId", new com.microsoft.playwright.options.SelectOption().setLabel("Retailer A"));
            page.fill("input.qty-input", "10");
            page.tap("button:has-text('Send for approval')");
            // wait for the order to be saved (the server redirects to /sales/orders/{id}) before moving on,
            // otherwise the next navigation can cancel the form submit
            page.waitForURL(java.util.regex.Pattern.compile(".*/sales/orders/\\d+$"));
            page.navigate(url("/sales/orders"));
            assertThat(page.locator("body")).containsText("Retailer A");
        }
    }

    @Test
    void ownerDashboardHasNoSidewaysScrollOnAPhone() {
        try (Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(375, 667).setIsMobile(true).setHasTouch(true))) {
            Page page = context.newPage();
            login(page, "owner");
            page.navigate(url("/owner"));
            assertThat(page.locator("#kpi-sales")).isVisible();
            Object overflow = page.evaluate("document.documentElement.scrollWidth - window.innerWidth");
            assertThat(((Number) overflow).intValue()).isLessThanOrEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void loginAndStockPageWorkInEveryBrowserEngine(String engineName) {
        try (Browser browser = engine(engineName).launch();
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            login(page, "prod");
            assertThat(page.locator("nav")).containsText("prod");
            page.navigate(url("/stock"));
            assertThat(page.locator("tr:has-text('Flour')")).containsText("100 kg");
            // a wrong role is refused in every engine too
            var response = page.navigate(url("/owner"));
            assertThat(response.status()).isEqualTo(403);
        }
    }
}
