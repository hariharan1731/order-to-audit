import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Records the section 5 benchmark as one captioned video (no audio). Run it through demo/record-demo.sh, which
 * starts a fresh seeded app first; the scenario changes data, so it needs an empty database every time.
 * Usage: java -cp <test classpath> demo/BenchmarkDemo.java <baseUrl> <outputFile.webm>
 *
 * Desktop steps run at 1280x800; salesman steps run inside a 390 px wide phone frame on the same page.
 * The app sends X-Frame-Options: DENY, so the recording browser (only) removes that header from page responses
 * to let the app load inside the phone frame. The application itself is not changed.
 */
public class BenchmarkDemo {

    static final String PASSWORD = "Demo@12345";
    static String base;
    static Page page;
    static BrowserContext context;

    // Captions live in sessionStorage so they survive page navigations; drawn only in the top frame.
    static final String CAPTION_INIT = """
        (() => {
          if (window.top !== window) return;
          const draw = () => {
            const raw = sessionStorage.getItem('demoCap');
            if (!raw || document.getElementById('phone-host')) return;
            const c = JSON.parse(raw);
            document.documentElement.style.scrollPaddingBottom = '130px';
            document.body.style.paddingBottom = '110px';
            let el = document.getElementById('demo-cap');
            if (!el) {
              el = document.createElement('div');
              el.id = 'demo-cap';
              el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:14px 28px 16px;'
                + 'background:rgba(8,20,16,.93);border-top:3px solid #34d399;color:#f1f5f9;'
                + 'font:16px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;pointer-events:none';
              document.body.appendChild(el);
            }
            el.innerHTML = '<div style="color:#34d399;font-weight:700;font-size:18px;margin-bottom:3px">' + c.t
              + '</div><div>' + c.b + '</div>';
          };
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', draw); else draw();
        })();
        """;

    public static void main(String[] args) throws Exception {
        base = args[0];
        Path out = Paths.get(args[1]);
        Path videoDir = out.getParent().resolve("raw");
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setSlowMo(120));
            context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1280, 800)
                    .setRecordVideoDir(videoDir)
                    .setRecordVideoSize(1280, 800));
            context.addInitScript(CAPTION_INIT);
            // Demo browser only: let the app's pages load inside the phone frame (the app itself sends DENY).
            context.route("**/*", route -> {
                if (!"document".equals(route.request().resourceType())) {
                    route.resume();
                    return;
                }
                var response = route.fetch(new Route.FetchOptions().setMaxRedirects(0));
                Map<String, String> headers = new HashMap<>(response.headers());
                headers.remove("x-frame-options");
                headers.remove("content-security-policy");
                route.fulfill(new Route.FulfillOptions().setResponse(response).setHeaders(headers));
            });
            page = context.newPage();
            page.setDefaultTimeout(20_000);
            try {
                run();
            } finally {
                Path video = page.video().path();
                context.close();
                java.nio.file.Files.copy(video, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                browser.close();
            }
        }
        System.out.println("Saved " + out);
    }

    // ------------------------------------------------------------------ the scenario

    static void run() {
        titleCard();

        // Step 1: purchase
        login(page, "prod");
        caption("Step 1 of 9 · Purchase 60 kg flour at Rs 45/kg",
                "Logged in as <b>prod</b> (production supervisor). Opening stock from the seed: flour 100 kg at Rs 42.");
        page.navigate(url("/stock"));
        show(page.locator("tr:has-text('Flour')").first(), 3500);
        page.navigate(url("/stock/purchases/new"));
        caption("Step 1 of 9 · Purchase 60 kg flour at Rs 45/kg",
                "The supervisor records the supplier bill once. Stock and the weighted-average cost update from it.");
        page.selectOption("#itemId-0", new SelectOption().setLabel("Flour (kg)"));
        type(page.locator("#quantity-0"), "60");
        type(page.locator("#rate-0"), "45");
        type(page.locator("#reference"), "BILL-1");
        hold(1200);
        page.click("#save-purchase");
        caption("Step 1 of 9 · Purchase recorded",
                "Flour is now 160 kg. The ledger row links to the purchase; average cost = (100 x 42 + 60 x 45) / 160 = Rs 43.125/kg.");
        show(page.locator("tr:has-text('Flour')").first(), 4500);

        // Steps 2 and 3: production run
        page.navigate(url("/production/new"));
        caption("Step 2 of 9 · Production run of Murukku 200 g",
                "The plan comes from the BOM: one batch = 40 kg flour, 10 L oil, 1.2 kg spice mix, 200 pouches, 10 cartons, 200 packets expected.");
        page.click("#show-plan");
        hold(4500);
        caption("Step 2 of 9 · Enter the actual issue",
                "Flour 40 kg and spice mix 1.2 kg as planned; oil 10.5 L (0.5 L over standard).");
        type(page.locator("tr:has-text('Oil') input[name=actual]"), "10.5");
        caption("Step 3 of 9 · Actual output",
                "188 good packets, 6 pouches damaged (194 used), 9 cartons used.");
        type(page.locator("tr:has-text('Pouch') input[name=actual]"), "194");
        type(page.locator("tr:has-text('Carton') input[name=actual]"), "9");
        type(page.locator("#goodPackets"), "188");
        type(page.locator("#damagedPouches"), "6");
        caption("Step 8 of 9 · Labour Rs 1,200 and gas/power Rs 600",
                "Overheads are entered on the run itself, so the run's cost per packet is complete the moment it is posted.");
        type(page.locator("#labour"), "1200");
        type(page.locator("#gasPower"), "600");
        hold(1200);
        page.click("#post-run");
        caption("Steps 2–3 · Run posted: stock moved automatically",
                "Yield 188 vs 200 = <b>-6%, flagged</b> (tolerance 3%). Oil 10.5 vs 10 L = <b>+5%, flagged</b>. Pouches -3% is not beyond 3%.");
        show(page.locator("#yield"), 3500);
        show(page.locator("tr:has-text('Oil')").first(), 4000);
        caption("Step 8 of 9 · Cost per packet",
                "Materials at weighted-average cost Rs 4,055 + overheads Rs 1,800 = Rs 5,855 ÷ 188 good packets = <b>Rs 31.14</b>; margin 37.71% at Rs 50.");
        show(page.locator("#cost-per-packet"), 6000);

        page.navigate(url("/stock"));
        caption("Stock after the run (from the ledger)",
                "Flour <b>120 kg</b> (100 + 60 - 40) · Oil <b>19.5 L</b> · Pouches <b>306</b> · Cartons 21 · Murukku <b>238</b> packets (50 + 188).");
        show(page.locator("tr:has-text('Flour')").first(), 1800);
        show(page.locator("tr:has-text('Oil')").first(), 1800);
        show(page.locator("tr:has-text('Pouch')").first(), 1800);
        show(page.locator("tr:has-text('Murukku')").first(), 3000);

        // Step 4: salesman A on the phone
        Frame phone = phoneMode("Step 4 of 9 · Salesman A on his phone",
                "Ravi picks the customer, the price comes from the price list, he types the quantity and sends. No phone call, no WhatsApp.");
        login(phone, "ravi");
        phone.navigate(url("/sales/orders/new"));
        hold(1500);
        phone.selectOption("#customerId", new SelectOption().setLabel("Retailer A"));
        hold(800);
        type(phone.locator("input.qty-input").first(), "150");
        hold(1200);
        phone.click("button[type=submit]:has-text('Send for approval')");
        phoneCaption("Step 4 of 9 · Order sent", "Retailer A (Tamil Nadu), 150 packets. Status: <b>SUBMITTED</b>.");
        hold(5000);

        // Step 5: admin approves, invoices and dispatches
        desktopAs("admin");
        page.navigate(url("/admin/orders"));
        caption("Step 5 of 9 · Admin order desk",
                "The order arrives in the approval queue straight from the phone.");
        hold(3000);
        page.locator("tr:has-text('Retailer A') a[href^='/admin/orders/']").first().click();
        caption("Step 5 of 9 · Stock check before approval",
                "238 packets on hand cover 150. The admin could still edit quantity or price here.");
        hold(4500);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Approve")).click();
        caption("Step 5 of 9 · Raise the GST invoice",
                "Same state (Tamil Nadu 33), so CGST + SGST. Numbered per financial year.");
        hold(2500);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Raise invoice")).click();
        hold(2500);
        caption("Step 5 of 9 · Dispatch",
                "Dispatch is the moment finished-goods stock goes down: 238 - 150 = 88.");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile("Dispatch.*"))).click();
        hold(3000);
        page.navigate(url("/admin/invoices"));
        page.locator("a[href^='/admin/invoices/']").first().click();
        caption("Step 5 of 9 · Invoice INV/2026-27/0001",
                "Rs 7,500 taxable + CGST Rs 187.50 + SGST Rs 187.50 = <b>Rs 7,875</b>. GSTIN and HSN on every line.");
        hold(6000);
        caption("Step 5 of 9 · Share with the customer",
                "Download the PDF, or open WhatsApp / e-mail with the invoice summary ready to send.");
        show(page.locator("#download-pdf"), 1800);
        show(page.locator("#share-whatsapp"), 1800);
        show(page.locator("#share-email"), 2200);
        page.navigate(url("/stock"));
        caption("Finished goods after dispatch", "Murukku 200 g: <b>88 packets</b>.");
        show(page.locator("tr:has-text('Murukku')").first(), 4000);

        phone = phoneMode("Back on the salesman's phone",
                "Ravi sees the status move to DISPATCHED, and his sales per customer.");
        login(phone, "ravi");
        phone.navigate(url("/sales/orders"));
        hold(4000);
        phone.locator("#customer-sales").scrollIntoViewIfNeeded();
        hold(2500);
        phone.locator("#customer-sales .table-responsive").evaluate("e => e.scrollTo({left: e.scrollWidth, behavior: 'smooth'})");
        hold(3500);

        // Step 6: Distributor B, insufficient stock
        phoneCaption("Step 6 of 9 · Salesman B: Distributor B, 120 packets",
                "Distributor B is in Karnataka. (Only one salesman login is seeded, so the same phone plays Salesman B.)");
        phone.navigate(url("/sales/orders/new"));
        hold(1500);
        phone.selectOption("#customerId", new SelectOption().setLabel("Distributor B"));
        type(phone.locator("input.qty-input").first(), "120");
        hold(1000);
        phone.click("button[type=submit]:has-text('Send for approval')");
        hold(3500);

        desktopAs("admin");
        page.navigate(url("/admin/orders"));
        page.locator("tr:has-text('Distributor B') a[href^='/admin/orders/']").first().click();
        caption("Step 6 of 9 · Insufficient stock is flagged",
                "Only 88 available, so the order is <b>short by 32</b> and Approve is disabled. Reduce to 88 or reject. Karnataka ≠ Tamil Nadu, so an invoice would carry <b>IGST</b>.");
        show(page.getByText(Pattern.compile("short 32")).first(), 4000);
        show(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Approve")), 4000);

        // Step 7: payment
        page.navigate(url("/admin/payments"));
        caption("Step 7 of 9 · Payment of Rs 4,000 from Retailer A",
                "Recorded against the invoice; the outstanding balance is derived, never typed.");
        hold(2500);
        page.locator("tr:has-text('Retailer A') a:has-text('Record payment')").first().click();
        type(page.locator("input[name=amountRupees]"), "4000");
        hold(1200);
        page.click("button[type=submit]:has-text('Record payment')");
        hold(2000);
        page.navigate(url("/admin/payments"));
        caption("Step 7 of 9 · Outstanding", "Retailer A owes Rs 7,875 - 4,000 = <b>Rs 3,875</b>.");
        show(page.locator("tr:has-text('Retailer A')").first(), 4500);

        // Step 9: reports
        desktopAs("accounts");
        page.navigate(url("/reports"));
        caption("Step 9 of 9 · Month-end reports for the auditor",
                "Logged in as <b>accounts</b> (read-only). Every report filters by date range and exports to CSV, Excel and PDF.");
        hold(4500);
        report("sales-register", "Sales register", "Every invoice with GSTIN, taxable value and tax.");
        report("customer-sales", "Customer-wise sales", "Sales per customer for the period.");
        report("product-sales", "Product-wise sales", "Quantity and value per product with HSN.");
        report("gst-summary", "GST summary (GSTR-1 / 3B)", "Outward supplies by B2B/B2C and rate, laid out to map onto GSTR-1 and GSTR-3B 3.1(a).");
        report("purchase-register", "Purchase register", "The 60 kg flour bill.");
        report("stock-summary", "Stock summary", "Opening + receipts - issues ± adjustments = closing, for raw, packaging and finished goods.");
        report("stock-ledger?itemId=1", "Stock ledger: Flour", "Every movement with its source document: opening, purchase BILL-1, production run.");
        report("production-consumption", "Planned vs actual consumption", "Oil +5% and cartons -10% flagged.");
        report("production-yield", "Production yield and wastage", "188 of 200 packets, -6%, 6 damaged pouches.");
        report("cost-per-packet", "Cost per packet and margin", "Weighted-average cost, overheads charged to the run: Rs 31.14 per packet, 37.71% margin.");
        report("collections", "Payments and collections", "The Rs 4,000 receipt.");
        report("outstanding", "Outstanding receivables with ageing", "Retailer A Rs 3,875, by age bucket.");
        show(page.locator("a:has-text('Excel')").first(), 2500);

        // Owner dashboard
        desktopAs("owner");
        page.navigate(url("/owner"));
        caption("Owner dashboard",
                "Sales Rs 7,875 · outstanding Rs 3,875 · 88 packets in stock · 1 flagged run · cost per packet Rs 31.14.");
        hold(1500);
        show(page.locator("#kpi-sales"), 1800);
        show(page.locator("#kpi-outstanding"), 1800);
        show(page.locator("#kpi-stock"), 1800);
        show(page.locator("#kpi-flagged"), 1800);
        show(page.locator("#kpi-cost"), 3000);

        // Audit trail and roles
        desktopAs("admin");
        page.navigate(url("/audit"));
        caption("Audit trail",
                "Every business action is logged with user and time, in the same transaction as the action.");
        hold(5000);
        desktopAs("ravi");
        caption("Role-based access",
                "The salesman's menu has only his orders. Production, office, reports and owner pages are refused with HTTP 403 even by direct URL (covered by the security tests).");
        show(page.locator("nav").first(), 6000);

        endCard();
    }

    // ------------------------------------------------------------------ helpers

    static String url(String path) {
        return base + path;
    }

    static void hold(long ms) {
        page.waitForTimeout(ms);
    }

    static void caption(String title, String body) {
        String json = "{\"t\":" + js(title) + ",\"b\":" + js(body) + "}";
        page.evaluate("c => { sessionStorage.setItem('demoCap', c); "
                + "const e = document.getElementById('demo-cap'); if (e) e.remove(); }", json);
        page.evaluate(CAPTION_INIT);
    }

    static String js(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Scrolls to an element and outlines it in gold for a moment. */
    static void show(Locator target, long ms) {
        try {
            target.scrollIntoViewIfNeeded();
            target.evaluate("e => { e.style.outline = '3px solid #fbbf24'; e.style.outlineOffset = '3px'; "
                    + "e.style.transition = 'outline-color .3s'; }");
            hold(ms);
            target.evaluate("e => { e.style.outline = ''; e.style.outlineOffset = ''; }");
        } catch (RuntimeException e) {
            hold(ms);
        }
    }

    static void type(Locator field, String text) {
        field.scrollIntoViewIfNeeded();
        field.click();
        field.fill("");
        field.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(110));
        hold(350);
    }

    static void login(Page p, String user) {
        p.navigate(url("/login"));
        type(p.locator("#username"), user);
        type(p.locator("#password"), PASSWORD);
        p.click("button[type=submit]");
        p.waitForURL(u -> !u.contains("/login"));
        hold(1200);
    }

    static void login(Frame f, String user) {
        f.navigate(url("/login"));
        type(f.locator("#username"), user);
        type(f.locator("#password"), PASSWORD);
        f.click("button[type=submit]");
        f.waitForURL(u -> !u.contains("/login"));
        hold(1200);
    }

    /** Signs out (drops the session cookie) and signs in again as another user on the full-size page. */
    static void desktopAs(String user) {
        context.clearCookies();
        caption("Switching user", "Signing in as <b>" + user + "</b>. Each role gets its own menu and permissions.");
        login(page, user);
    }

    /** Turns the page into a phone mock-up with a 390 px wide iframe, and a caption panel beside it. */
    static Frame phoneMode(String title, String body) {
        context.clearCookies();
        page.navigate(url("/login"));
        page.evaluate("() => sessionStorage.removeItem('demoCap')");
        page.evaluate("""
            ([t, b]) => {
              document.head.innerHTML = '<meta charset="utf-8">';
              document.body.removeAttribute('class');
              document.body.style.cssText = 'margin:0;height:100vh;display:flex;align-items:center;justify-content:center;'
                + 'gap:56px;background:radial-gradient(circle at 30% 20%,#0f2a22,#05100c);font-family:-apple-system,Segoe UI,Roboto,sans-serif';
              document.body.innerHTML =
                '<div id="phone-host" style="width:410px;height:770px;border-radius:44px;background:#0b0b0b;padding:12px 10px;'
                + 'box-shadow:0 0 0 2px #2b2b2b,0 30px 80px rgba(0,0,0,.6);box-sizing:border-box">'
                + '<iframe name="phone" style="width:390px;height:746px;border:0;border-radius:32px;background:#fff"></iframe></div>'
                + '<div style="width:420px;color:#e2e8f0"><div style="color:#34d399;font-size:13px;letter-spacing:.12em;'
                + 'text-transform:uppercase;margin-bottom:10px">Mobile salesman view · 390 px</div>'
                + '<div id="phone-title" style="font-size:26px;font-weight:700;margin-bottom:12px"></div>'
                + '<div id="phone-body" style="font-size:18px;line-height:1.5;color:#cbd5e1"></div></div>';
              document.getElementById('phone-title').innerHTML = t;
              document.getElementById('phone-body').innerHTML = b;
            }""", java.util.List.of(title, body));
        hold(300);
        Frame f = page.frame("phone");
        return f;
    }

    static void phoneCaption(String title, String body) {
        page.evaluate("([t, b]) => { document.getElementById('phone-title').innerHTML = t; "
                + "document.getElementById('phone-body').innerHTML = b; }", java.util.List.of(title, body));
    }

    static void report(String path, String title, String body) {
        page.navigate(url("/reports/" + path));
        caption("Step 9 of 9 · " + title, body);
        hold(4200);
    }

    static void card(String html, long ms) {
        page.setContent("<html><body style=\"margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 30% 20%,#0f2a22,#05100c);color:#f1f5f9;"
                + "font-family:-apple-system,Segoe UI,Roboto,sans-serif;text-align:center\"><div>" + html + "</div></body></html>");
        hold(ms);
    }

    static void titleCard() {
        page.navigate(url("/login"));       // same origin, so captions can use sessionStorage later
        card("<div style=\"color:#34d399;letter-spacing:.14em;text-transform:uppercase;font-size:15px\">"
                + "Order-to-Audit · NIT Tiruchirappalli internship task</div>"
                + "<div style=\"font-size:44px;font-weight:800;margin:14px 0\">Section 5 benchmark, end to end</div>"
                + "<div style=\"font-size:20px;color:#cbd5e1;max-width:860px;margin:auto;line-height:1.5\">"
                + "Purchase → production run against the BOM → salesman order on a phone → approval → GST invoice → dispatch "
                + "→ payment → cost per packet → month-end reports. Fresh database loaded with the brief's master data.</div>", 7000);
    }

    static void endCard() {
        card("<div style=\"font-size:40px;font-weight:800;margin-bottom:18px\">Every benchmark number matches</div>"
                + "<div style=\"font-size:20px;color:#cbd5e1;line-height:1.7\">Flour 120 kg · Oil 19.5 L · Pouches 306 · "
                + "Finished goods 238 → 88<br>Yield -6% flagged · Oil +5% flagged · Invoice Rs 7,875 · Outstanding Rs 3,875<br>"
                + "Distributor B short by 32 (IGST) · Cost per packet Rs 31.14 (weighted average, overheads per run)</div>"
                + "<div style=\"margin-top:26px;color:#34d399\">All of these are also asserted by the automated test suite.</div>", 8000);
    }
}
