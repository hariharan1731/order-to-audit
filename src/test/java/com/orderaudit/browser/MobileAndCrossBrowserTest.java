package com.orderaudit.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    /*
     * Firefox runs inside Playwright's official Linux image instead of on the host. Playwright's bundled
     * Firefox never finishes starting on macOS 27.2, while the same build runs normally on Linux.
     * The image tag must match the Playwright version in pom.xml, because client and server versions must agree.
     */
    private static final String PLAYWRIGHT_VERSION = "1.52.0";
    private static final int BROWSER_SERVER_PORT = 3000;

    static Playwright playwright;
    static GenericContainer<?> firefoxServer;

    /** Host name the browser uses to reach the app: localhost, or the Docker host when the browser is in a container. */
    private String appHost = "localhost";

    @BeforeAll
    static void start() {
        playwright = Playwright.create();
    }

    @AfterAll
    static void stop() {
        playwright.close();
        if (firefoxServer != null) {
            firefoxServer.stop();
            firefoxServer = null;
        }
    }

    private String url(String path) {
        return "http://" + appHost + ":" + port + path;
    }

    private Browser launch(String name) {
        return switch (name) {
            case "firefox" -> connectToContainerFirefox();
            case "webkit" -> playwright.webkit().launch();
            default -> playwright.chromium().launch();
        };
    }

    private Browser connectToContainerFirefox() {
        if (firefoxServer == null) {
            // Must happen before the container starts, so it can reach this test's server on the host.
            org.testcontainers.Testcontainers.exposeHostPorts(port);
            firefoxServer = new GenericContainer<>("mcr.microsoft.com/playwright:v" + PLAYWRIGHT_VERSION + "-noble")
                    .withCommand("npx", "-y", "playwright@" + PLAYWRIGHT_VERSION, "run-server",
                            "--port", String.valueOf(BROWSER_SERVER_PORT), "--host", "0.0.0.0")
                    .withExposedPorts(BROWSER_SERVER_PORT)
                    .waitingFor(Wait.forLogMessage(".*Listening on.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(5));
            firefoxServer.start();
        }
        appHost = "host.testcontainers.internal";
        return playwright.firefox().connect(
                "ws://" + firefoxServer.getHost() + ":" + firefoxServer.getMappedPort(BROWSER_SERVER_PORT) + "/");
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
        try (Browser browser = launch(engineName);
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
