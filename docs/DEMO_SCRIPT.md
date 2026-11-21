# Demo script (about 8 minutes)

Start: `docker compose up --build`, open http://localhost:8080. Password for every demo user: `Demo@12345`.

1. **Show the story (30 s).** "Order to audit: every step is recorded, nothing is overwritten."
2. **Production supervisor (`prod`).**
   - Stock → *Record purchase*: Flour 60 kg at ₹45 → stock becomes 160 kg.
   - Production → *New run* → Show plan (recipe: 40 kg flour, 10 L oil, 1.2 kg spice, 200 pouches, 10 cartons).
   - Type actual oil **10.5**, pouches **194**, cartons **9**, good packets **188**, damaged **6**, labour **1200**, gas/power **600** → Post.
   - Point out: yield **−6% flagged**, oil **+5% flagged**, cost/packet **₹31.14**.
   - Stock page: flour 120 kg, oil 19.5 L, pouches 306, finished goods 238.
3. **Salesman (`ravi`).** New order → Retailer A → 150 packets → send. "He sees only his own orders."
4. **Admin (`admin`).** Order desk → open order → Approve → Raise invoice (**INV/2026-27/0001**, ₹7,500 + 187.50 + 187.50 = **₹7,875**) → Dispatch. Download the PDF.
5. **Payment.** Payments → Retailer A → ₹4,000 → outstanding **₹3,875**.
6. **Shortage story.** Finished goods now 88. A 120-packet order for Distributor B is short by **32** (IGST because Karnataka).
7. **Accountant (`accounts`).** Reports → Receivables ageing, GST summary → download CSV/Excel/PDF. Try to change stock → refused.
8. **Owner (`owner`).** Dashboard: sales, outstanding, stock, flagged runs, cost, margin **37.71%**.
9. **Audit (`admin`).** Audit log shows who did each step. Mention: a database trigger blocks editing it.
10. **Credit note (optional).** Cancel an unpaid dispatched invoice → CN/2026-27/0001, stock returns.

Closing line: "Integer paise, append-only ledgers, every rule tested — 398 tests including a real browser run."
