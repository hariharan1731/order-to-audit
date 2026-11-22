# Order-to-Audit System

An individual internship project for NIT Tiruchirappalli (Centre for Digital Infrastructure). It runs the whole
trail of a small food manufacturer (benchmark: "Murukku"): **purchase → production → order → approval → invoice
→ dispatch → payment → reports → audit log**.

**Stack:** Java 21, Spring Boot 4.1.0, PostgreSQL 16, Spring Security, Spring Data JPA, Thymeleaf, Bootstrap 5,
Maven, Flyway, Docker Compose. Tests: JUnit 5, Testcontainers, MockMvc, Playwright.

**Why this stack:** the hard part of this problem is correct, traceable money and stock, so I chose a strongly typed
backend (Java + Spring) on a real relational database (PostgreSQL) where constraints, transactions, row locks and
triggers protect the ledger even if the Java code has a bug. Server-rendered Thymeleaf pages with Bootstrap work on a
cheap phone and on the office desktop with no separate mobile app to install, and Docker Compose gives the reviewer a
single command to run everything.

## Submission links

| Item | Link |
|---|---|
| Public repository | https://github.com/hariharan1731/order-to-audit |
| Demo video (section 5 scenario, 5 min 23 s) | https://drive.google.com/file/d/1FdpUWpP3ivZG2aWXgaCVW6FEQbEWllfE/view?usp=sharing |
| Live demo | _none; run locally with the single command below_ |

## Run it (one command)

Needs Docker Desktop.

```bash
docker compose up --build
```

Open http://localhost:8080. First start loads the official benchmark data and one demo login per role
(password for all: `Demo@12345` — demo only, change before any real use).

| Login | Role | Can do |
|---|---|---|
| `ravi` | Salesman | Create orders, see own orders |
| `suresh` | Salesman | A second salesman ("Salesman B" in the benchmark) |
| `admin` | Admin | Approve, invoice, dispatch, payments, customers, prices, users, audit log |
| `prod` | Production supervisor | Production runs, purchases, stock |
| `accounts` | Accountant | Reports, look at stock (read-only) |
| `owner` | Owner | Everything, plus the dashboard |

Only the database (to run the app from your IDE): `docker compose up -d db`, then run `OrderAuditApplication`
(set `APP_SEED_ENABLED=true` to load demo data).

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/orderaudit` | JDBC URL of PostgreSQL |
| `DB_USER` | `orderaudit` | Database user |
| `DB_PASSWORD` | `orderaudit_dev` | Database password (**change in production**) |
| `APP_SEED_ENABLED` | `false` | `true` loads the official benchmark data and demo users on an empty database |
| `APP_SEED_PASSWORD` | `Demo@12345` | Password given to the demo users when seeding |
| `SERVER_PORT` | `8080` | HTTP port |

The schema is created by Flyway migrations (`src/main/resources/db/migration`) on startup; nothing is created by hand.
Seeding is done by `DataSeeder` (safe to run twice).

## Deployment

1. A Linux server with Docker and Docker Compose.
2. Copy the project, create a `.env` file next to `docker-compose.yml` with a strong `DB_PASSWORD`.
3. For real use set `APP_SEED_ENABLED` to `false` (remove it from the `app` service) and create users from the Users screen;
   for a demo keep it `true` and change the demo password with `APP_SEED_PASSWORD`.
4. `docker compose up -d --build`.
5. Put a reverse proxy (nginx / Caddy) in front for HTTPS, and do not expose port 5432 publicly.
6. Back up the `orderaudit_data` volume (`pg_dump`) regularly.

## Record the demo video

```bash
demo/record-demo.sh
```

Plays the whole section 5 scenario in a real browser against a fresh seeded copy of the app (throwaway database on
port 55432, app on port 8099; both are removed afterwards) and saves a captioned 1280x800 video, about 5 minutes,
to `target/demo/order-to-audit-benchmark-demo.webm`. Salesman steps are shown in a 390 px phone frame. The video
has no audio; add a voice-over before uploading if you want one. Script: `demo/BenchmarkDemo.java`.

## Run the tests

Needs Docker running (tests start a throwaway PostgreSQL) and internet the first time (Playwright downloads Chromium and WebKit, and Testcontainers pulls the Playwright Firefox image).

```bash
mvn test        # Firefox runs in a Playwright Docker image, see "Firefox on macOS 27" below
```

## Layers

`Controller (web) → Service → Repository → PostgreSQL`. Controllers only translate HTTP; all rules live in
services; repositories only talk to the database.

## Rules the system enforces

- **Money is integer paise** (never float/double). Rates are paise per base unit (g, ml, pcs) with 4 decimals.
- **Stock movements are append-only**: a database trigger rejects UPDATE and DELETE. Mistakes are fixed by
  *reversal rows*, never by editing history. The same applies to the audit log and credit notes.
- **Soft reservation** is derived: available = on hand − approved-but-undispatched orders. Nothing is stored twice.
- **Costing:** weighted-average cost (WAC). Yield and material variances use a strict `>` 3% tolerance.
- **Invoices:** gapless `INV/2026-27/0001` per financial year (Apr–Mar); GST 5% split CGST+SGST in Tamil Nadu,
  IGST for other states. Credit notes `CN/2026-27/0001`.
- **Audit:** every business action writes an audit row inside the same transaction; a failed action leaves none.
- **Security:** form login, BCrypt passwords, CSRF on, role-based URL rules, deny by default.

## Features against the brief's tiers (section 6)

**Must have**
| Requirement | Status | Where |
|---|---|---|
| Login with at least three roles | Done (5 roles) | Login page; role rules in `SecurityConfig`, tested per role |
| Product, customer, raw-material and BOM masters | Done | Office menu: Items, Customers, Prices, Recipes and packs (`/admin/setup`) |
| Mobile-friendly order entry and admin approval | Done | Salesman: New order. Admin: Order desk with live stock check |
| GST invoice as PDF | Done | Invoice page: Download PDF, plus WhatsApp and Email share buttons |
| Production run updates raw and finished stock automatically | Done | Production: post a run, one transaction writes every stock movement |
| Planned vs actual consumption and yield, variance shown | Done | Run page and Production reports |
| Stock summary and stock ledger | Done | Stock screen, item history, Stock summary and Stock ledger reports |
| Sales register and stock report, exportable to Excel/CSV | Done | Reports: every report exports to CSV, Excel and PDF |

**Should have**
| Requirement | Status | Where |
|---|---|---|
| Purchases and supplier records | Done | Stock: Record a purchase; Office: Suppliers; Purchase register |
| Payments, outstanding and ageing | Done | Office: Payments (with reversal); Outstanding receivables with ageing |
| Cost per packet with overheads | Done | Run page; Cost per packet and margin report |
| GST summary report | Done | GST summary of outward supplies (B2B/B2C by rate, maps to GSTR-1 and GSTR-3B 3.1(a)) |
| Variance alerts beyond a tolerance | Done | Flagged on the run page and counted on the owner dashboard (tolerance set per recipe, default 3%) |
| Owner dashboard | Done | KPI cards, sales-per-day and ageing charts, biggest debtors |

**Stretch goals:** none built yet (offline capture, batch/expiry, low-stock alerts, production planning from orders,
visit log, Tally export, e-Invoice format, Tamil interface). Two things in that direction do exist: a double-tapped
order on a weak network is saved only once (request id), and a payment can be reversed without editing history.

## Costing method and overhead rule

- **Valuation: weighted-average cost (WAC).** Each receipt updates the item's average cost from the ledger; an issue
  is valued at the average at that moment. Chosen because flour from two purchases (Rs 42 and Rs 45) is mixed in the
  same bin, so batch-exact FIFO would claim a precision the factory floor does not have. The benchmark flour issue is
  40 kg x Rs 43.125 = Rs 1,725.
- **Materials are charged at actual use**, not at the BOM standard: 10.5 L of oil and all 194 pouches (6 damaged) are
  real cost.
- **Overhead rule: overheads are charged to the run they were spent on.** Labour, gas and power are entered on the
  production run (any number of lines), added to the run's material cost, and the total is divided by the **good
  packets only**. Damaged output and over-use therefore raise the cost of the good packets, which is what the owner
  needs to see. Monthly overheads that cannot be tied to one run (rent, salaries) are not allocated in this version.
- **Cost per packet** = (materials + overheads) / good packets = Rs 5,855 / 188 = **Rs 31.14**; margin against the
  Rs 50 list price = **37.71%**. The good packets enter finished stock at that unit cost.

## Assumptions

- The factory is in Tamil Nadu (state code 33). A customer in state 33 gets CGST + SGST, any other state gets IGST.
- GST 5% on Murukku as the brief says; HSN code and rate are stored per item and copied onto each invoice line.
- Invoice numbers restart every financial year (April to March): `INV/2026-27/0001`.
- Stock is held in whole base units (grams, millilitres, pieces, packets), so 10.5 L is stored as 10,500 ml.
- Cartons are a packaging item consumed by production, not a sales unit; customers order in packets.
- The 3% variance tolerance is strict: exactly 3% (the pouches, 194 vs 200) is **not** flagged, more than 3% is.
- Overheads are entered on the run when it is posted (brief step 8's figures are typed in during step 3; the demo
  shows the result at step 8). To correct a
  posted run it is reversed and posted again; nothing is edited in place.
- Opening stock comes from the seed data; Murukku's 50 opening packets have no known cost, so they are left unvalued.
- An order needs enough available stock to be approved (available = on hand minus approved orders not yet dispatched).
  The admin can lower the quantity, for example Distributor B to 88, or reject it.
- Distributor B is in Karnataka (29). GSTINs in the seed data are fictional placeholders; in the GST summary a
  customer with a GSTIN is B2B and one without is B2C.

## Official benchmark (verified by tests)

| Check | Result |
|---|---|
| Stock after the run | flour 120 kg, oil 19.5 L, pouches 306, finished goods 238 |
| Yield | −6% → flagged (tolerance 3%) |
| Oil usage | +5% → flagged |
| Retailer A invoice | ₹7,500 + CGST 187.50 + SGST 187.50 = **₹7,875** |
| After ₹4,000 payment | outstanding **₹3,875** |
| Cost per packet | 3114.3617 paise = **₹31.14**, margin 37.71% |
| Distributor B | 238 − 150 = 88 left, so a 120-packet order is short by 32 |

## The screens

| Screen | Who | What it does |
|---|---|---|
| Home | everyone | Role dashboard: the few live numbers that role is allowed to see, plus shortcuts |
| My orders | salesman | Summary cards (total, pending, submitted, approved, completed), search by order number or retailer, status and date filters, sorting, a data table with products, quantity, amount, GST and status, and **my sales by customer** (billed and waiting, per customer) |
| New order | salesman | Phone-friendly: customer, list price per product, +/- quantity, one tap to send |
| Order desk | office | The same cards, search and filters, plus the three work queues (approve, invoice, dispatch) |
| One order | office | Progress steps, live stock check with shortage warning, line editing, approve/reject, invoice, dispatch, invoice PDF with WhatsApp and Email share |
| Production | supervisor | Plan from the recipe, actual usage in kg/L, variance flags, cost per packet, reverse a run |
| Stock | supervisor, accountant | Stock now, item history, record a purchase |
| Reports | accountant | 12 reports on screen, each exportable to CSV, Excel and PDF |
| Owner dashboard | owner | KPI cards, sales-per-day chart, money-owed-by-age chart, biggest debtors |
| Office menu | admin | Invoices, payments, customers, prices, items, recipes and packs, users, audit log |

## Firefox on macOS 27: browser test runs Firefox in Docker

`MobileAndCrossBrowserTest.loginAndStockPageWorkInEveryBrowserEngine` runs the same login and stock-page checks in
Chromium, Firefox and WebKit. On a Mac running **macOS 27.2 (Apple silicon)** the Firefox case errors with
`TimeoutError: Timeout 180000ms exceeded` in `Firefox.launch`, before the app is ever opened. The Chromium and WebKit
cases and every other test pass. The test is deliberately **not** skipped or disabled.

- **Cause:** Firefox itself, not this project. Playwright 1.52.0 bundles Firefox 137. On macOS 27.2 its main thread
  spins at 100% CPU in the native event loop and never finishes start-up, so it never answers Playwright. The log lines
  `Can't find profile directory`, `sandbox_extension_issue_file_to_process ... Operation not permitted` and
  `RenderCompositorSWGL failed mapping default framebuffer` are side effects of that stall.
- **Not the fix:** Playwright 1.65.1 (Firefox 159) was also tried. Firefox 159 no longer hangs but exits immediately
  with `Could not find profile folder.` A different profile location, Firefox's sandbox environment variables and
  other command-line variants made no difference. Chromium and WebKit launch normally in both Playwright versions.
- **Fix (in place):** the Firefox case now runs Firefox inside Playwright's official Linux image
  (`mcr.microsoft.com/playwright:v1.52.0-noble`), started by Testcontainers. The test connects with
  `playwright.firefox().connect(...)`, and the browser reaches the app through `host.testcontainers.internal`.
  Chromium and WebKit still launch on the host, and every assertion is unchanged. The image tag must match the Playwright
  version in `pom.xml`. The first run downloads the image (about 2 GB) and needs internet to fetch the matching
  `playwright` npm package inside it.
- **Expected result:** `mvn test` passes with 0 errors on macOS 27.2.

## Known limitations

- Recipes and pack configurations can be created but not edited afterwards (create a new one instead).
- Items cannot be renamed, and their type and unit are fixed once stock has moved.
- Credit notes cover a whole dispatched invoice with no payments (no partial returns).
- Bootstrap, Bootstrap Icons and Chart.js load from a CDN, so the browser needs internet (the pages still work without it; icons and charts simply do not appear).
- Offline order entry, FEFO batches and low-stock alerts are not built (stretch goals).
- GSTINs are fictional placeholders.
