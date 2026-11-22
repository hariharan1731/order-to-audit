# Demo script (about 6 minutes, section 5 in order)

Start: `docker compose up --build`, open http://localhost:8080. Password for every demo user: `Demo@12345`.
A captioned recording of exactly this script can be made with `demo/record-demo.sh`.

0. **Story (20 s).** "Order to audit: every transaction is entered once, at its source; nothing is overwritten."
1. **Purchase, `prod`.** Stock shows flour 100 kg. *Record purchase*: Flour 60 kg at ₹45, bill BILL-1 → flour 160 kg.
   Weighted-average cost becomes (100 × 42 + 60 × 45) / 160 = ₹43.125/kg.
2. **Production run, `prod`.** Production → *New run* → Murukku 200 g → *Show plan*: 40 kg flour, 10 L oil, 1.2 kg spice,
   200 pouches, 10 cartons, 200 packets expected. Actual oil **10.5**.
3. **Actual output.** Good packets **188**, damaged pouches **6** (pouches used 194), cartons **9**. Labour **1200** and
   gas/power **600** go on the same form (they are shown at step 8). Post.
   Point out: yield **−6% flagged**, oil **+5% flagged**, pouches −3% not flagged (tolerance is "more than 3%").
   Stock: flour **120 kg**, oil **19.5 L**, pouches **306**, Murukku **238**.
4. **Salesman A on a phone, `ravi`.** New order → Retailer A → 150 packets → *Send for approval* (under a minute).
5. **Admin, `admin`.** Order desk → open the order → stock check (238 covers 150) → *Approve* → *Raise invoice*
   **INV/2026-27/0001**: ₹7,500 + CGST 187.50 + SGST 187.50 = **₹7,875** → *Dispatch* → Murukku **88**.
   Invoice page: *Download PDF*, *WhatsApp*, *Email*. Back on Ravi's phone: DISPATCHED and "My sales by customer".
6. **Salesman B on a phone, `suresh`.** Distributor B (Karnataka) → 120 packets. Admin opens it: **short by 32**,
   *Approve* disabled, amounts show **IGST (expected)** because Karnataka is not Tamil Nadu.
7. **Payment, `admin`.** Payments → Retailer A → ₹4,000 → outstanding **₹3,875**. (A double-click records it once.)
8. **Cost per packet, `prod`.** Open run #1: labour ₹1,200 + gas/power ₹600; materials ₹4,055 + overheads ₹1,800 =
   ₹5,855 ÷ 188 good packets = **₹31.14**, margin **37.71%** at ₹50.
9. **Reports, `accounts`.** Sales register, customer-wise and product-wise sales, GST summary (GSTR-1 / 3B), purchase
   register, stock summary, stock ledger of flour, planned vs actual consumption, yield and wastage, cost per packet,
   collections, outstanding with ageing. Each one: CSV, Excel, PDF.

Then, if time allows:
- **Owner, `owner`.** Dashboard: sales ₹7,875, outstanding ₹3,875, 88 packets, 1 flagged run, ₹31.14, 37.71%.
- **Audit, `admin`.** Audit log: purchase, order submitted, approved, invoiced, dispatched, payment, production run, each
  with user and time. A database trigger blocks editing it.
- **Roles.** Ravi's menu shows only his orders; opening /owner gives the "Not allowed" page (403).

Closing line: "Integer paise, append-only ledgers, every benchmark number asserted by the automated tests."
