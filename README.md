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

## Run the tests

Needs Docker running (tests start a throwaway PostgreSQL) and internet the first time (Playwright downloads Chromium).

```bash
mvn test        # 398 tests
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

## Known limitations

- Items, recipes, pack configs and opening stock have no admin screens (they are seeded).
- Per-customer special prices exist in the service but have no screen.
- Credit notes cover a whole dispatched invoice with no payments (no partial returns).
- Bootstrap loads from a CDN, so the browser needs internet.
- Offline order entry, FEFO batches and low-stock alerts are not built (stretch goals).
- GSTINs are fictional placeholders.
