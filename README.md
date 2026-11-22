# Order-to-Audit System

An individual internship project for NIT Tiruchirappalli (Centre for Digital Infrastructure). It runs the whole
trail of a small food manufacturer (benchmark: "Murukku"): **purchase → production → order → approval → invoice
→ dispatch → payment → reports → audit log**.

**Stack:** Java 21, Spring Boot 4.1.0, PostgreSQL 16, Spring Security, Spring Data JPA, Thymeleaf, Bootstrap 5,
Maven, Flyway, Docker Compose. Tests: JUnit 5, Testcontainers, MockMvc, Playwright.

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

## Run the tests

Needs Docker running (tests start a throwaway PostgreSQL) and internet the first time (Playwright downloads Chromium).

```bash
mvn test        # 474 tests (on macOS 27 the Firefox browser case errors, see "Known issue" below)
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
| My orders | salesman | Summary cards (total, pending, submitted, approved, completed), search by order number or retailer, status and date filters, sorting, and a data table with products, quantity, amount, GST and status |
| New order | salesman | Phone-friendly: customer, list price per product, +/- quantity, one tap to send |
| Order desk | office | The same cards, search and filters, plus the three work queues (approve, invoice, dispatch) |
| One order | office | Progress steps, live stock check with shortage warning, line editing, approve/reject, invoice, dispatch, invoice PDF |
| Production | supervisor | Plan from the recipe, actual usage in kg/L, variance flags, cost per packet, reverse a run |
| Stock | supervisor, accountant | Stock now, item history, record a purchase |
| Reports | accountant | 12 reports on screen, each exportable to CSV, Excel and PDF |
| Owner dashboard | owner | KPI cards, sales-per-day chart, money-owed-by-age chart, biggest debtors |
| Office menu | admin | Invoices, payments, customers, prices, items, recipes and packs, users, audit log |

## Known issue: Firefox browser test fails on macOS 27

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
- **Expected result on macOS 27.2:** `mvn test` reports exactly **1 error** (the Firefox case), after about 3 minutes.
- **Plan:** run the Firefox case on a Linux CI machine, where Playwright's Firefox is supported. Not yet verified,
  so treat that as a plan, not a result. Nothing in the project configuration was changed for this issue.

## Known limitations

- Recipes and pack configurations can be created but not edited afterwards (create a new one instead).
- Items cannot be renamed, and their type and unit are fixed once stock has moved.
- Credit notes cover a whole dispatched invoice with no payments (no partial returns).
- Bootstrap, Bootstrap Icons and Chart.js load from a CDN, so the browser needs internet (the pages still work without it; icons and charts simply do not appear).
- Offline order entry, FEFO batches and low-stock alerts are not built (stretch goals).
- GSTINs are fictional placeholders.
