# Final evaluation report

Status of each requirement from the internship brief, with how it was verified.
"Verified" means an automated test passes on a real PostgreSQL (Testcontainers). The full suite is run with `mvn test`.

## Modules
| Requirement | Status | Evidence |
|---|---|---|
| Role-based login (5 roles) | Done | Security rules tests (10), role checks in every web test, browser login test |
| Customer and product management | Done | Customers, Items (add), Prices incl. special prices; audited |
| Mobile-friendly salesman order | Done (layout) | Bootstrap responsive, large tap targets. Not tested on a physical phone |
| Admin approve / reject | Done | Web order flow tests; shortage blocks approval |
| GST invoice + PDF | Done | Invoice tests, PDF content test (Rs 7,500 / 187.50 / 187.50 / 7,875), browser PDF download |
| Share invoice with customer | Done | WhatsApp and Email buttons on the invoice page carry the totals; the PDF is attached by the admin. Tested in `WebOfficeIntegrationTest` |
| Purchase and supplier management | Done | Purchase screen + supplier form + tests |
| Raw/packaging inventory, stock ledger | Done | Append-only ledger, item history screen, ledger tests |
| BOM | Done | Recipes and pack configs are created on `/admin/setup` (the benchmark one comes from the seed); not editable after creation |
| Production run, auto deduction/addition | Done | Production service + web + browser tests |
| Planned vs actual, yield, wastage | Done | Run page shows planned vs actual, variance flags, damaged pouches |
| Customer payments, outstanding | Done | Payment allocation tests, ageing report; payment reversal (bounced cheque) with race tests |
| Duplicate payment protection | Done | One-time request id on the payment form + unique column (V13); tested in `ReceivablesIntegrationTest` and `WebOfficeIntegrationTest` |
| Salesman's customer-wise sales | Done | "My sales by customer" on My orders (billed and waiting per customer). Tested in `WebOrderDashboardIntegrationTest` |
| Cost per packet and margin | Done | Rs 31.14 and 37.71% asserted in service, report and browser tests |
| Monthly reports + CSV/Excel/PDF | Done | 12 reports, exports tested (content and format) |

## Benchmark
Every expected value is asserted (see `docs/BENCHMARK.md`): stock 120 kg / 19.5 L / 306 / 238 / 88, yield -6%, oil +5%,
Rs 7,500 + 187.50 + 187.50 = Rs 7,875, outstanding Rs 3,875, Distributor B shortage 32.

## Database and backend
Foreign keys and CHECK constraints on every table; stock movements link to their source document
(`source_type`/`source_id`); production and dispatch are single transactions; integer paise and BigDecimal only;
append-only triggers on stock movements, audit log and credit notes; audit rows for orders, invoices, dispatch,
payments, production, users, items, prices, customers and suppliers; invoice cancellation by credit note;
server-side validation on every form; CSRF, BCrypt, deny-by-default URL rules.

## UI/UX
A single design system (`static/css/app.css`), the "Midnight Emerald" dark theme: every colour is a token
(`--oa-*`) defined once at the top of the file, and Bootstrap's own colour variables are pointed at those tokens
(pages set `data-bs-theme="dark"`), so forms, tables, dropdowns and alerts all follow the theme. One card style,
one table style, one set of status colours (submitted blue, pending gold, approved emerald, invoiced teal,
dispatched green, rejected red, cancelled grey; each badge also shows an icon and the word, never colour alone),
icons from Bootstrap Icons. Text contrast checked against WCAG AA (body text 13:1, secondary 7:1, badges 5.6:1+). Top bar highlights the section you are in. Every list screen
has summary cards, search, status and date filters and sorting. Order screens show the four workflow steps.
Flash success/error messages, empty states on every list, loading spinner and double-submit protection on every
form button. Tables scroll inside their card on a phone instead of breaking the page.

A smoke test (`WebNavigationSmokeIntegrationTest`) opens every screen, every one of the twelve reports and every
link in the menu with real data behind it and insists on HTTP 200, so a broken page cannot reach the demo.

## Audit follow-up: what was found and fixed after review
A self-review of the finished system produced a prioritised list. Every item below was fixed with new tests, and the
official benchmark values were never changed to make a test pass. The suite now has 583 tests, all passing.

| Item | Problem found | Fix |
|---|---|---|
| C1 | Two people acting on the same order at the same moment could both succeed | Order rows locked while a status changes (`findForUpdate`); race tests with real threads |
| C2 | An admin could take over the business by creating or resetting an owner | Only an owner may create an owner, reset an owner's password or enable/disable an owner; the last active owner can never be disabled |
| H1 | A bounced cheque could not be undone | Payment reversal: a new append-only `payment_reversal` row, the invoice owes the money again, both rows stay in history |
| H2 | Changing an order line left no record of what changed | `ORDER_LINE_CHANGED` audit row with old and new quantity and price |
| H3 | Stock adjustments and sign-ins were not audited | Opening stock, corrections, good and failed sign-ins all go to the audit log (passwords never logged) |
| H5 | Opening stock and corrections had no screen | Stock screen with reason required, exact kg/L conversion, role checks |
| H6 | Impossible dates were accepted | No future payment, reversal, purchase or run dates; a payment cannot be dated before its invoice |
| M1 | Dashboard counted reversed runs as flagged | Only posted runs count |
| M2 | Ageing "as of" a past date used later payments | Only payments and reversals up to that date count |
| M3 | Dashboard cost and margin did not say which run they came from | The run is named; "Orders today" added |
| M4 | Dispatching after stock disappeared crashed to an error page | Normal message on the order page |
| M5 | A disabled user kept their session; unlimited password guesses | Signed out on the next click; 5 wrong passwords lock a username for 15 minutes |
| M6 | Customers and suppliers could not be corrected or switched off | Edit and switch off/on screens, audited; old invoices keep their original details |
| M9 | Credit notes were hidden inside the GST sales rows | Separate "credit notes" rows; the total is still the net figure |

Still open (low impact): M7 overheads can only be entered when a run is created; M8 a returned order still shows
"Dispatched" (its invoice shows Cancelled); M10 the invoice PDF's seller state text is fixed at Tamil Nadu (33);
M11 pouch counts are not cross-checked against packets.

## Known limitations (honest list)
- Recipes and pack configurations can be created from the screen but not edited afterwards.
- Items: tax details can be changed and an item can be switched off, but names, types and units are fixed.
- Opening stock and manual corrections are entered from the Stock screen ("Opening / correction"), always with a reason; there is no separate "issue raw material" action (production runs issue raw material, and write-offs use a correction).
- Credit notes cancel a whole dispatched invoice with no payments; no partial returns.
- Excel exports write numeric cells via a double at the final write step (display only; calculations are in paise).
- Bootstrap and Chart.js load from a CDN; the browser needs internet (charts degrade gracefully).
- Not load-tested. The browser tests run Chromium, Firefox and WebKit (Safari's engine) at desktop and phone size, but not on a real handset.
- Offline order entry, FEFO batches and low-stock alerts are not built (stretch goals).
- GSTINs are fictional placeholders; no integration with the GST portal.
