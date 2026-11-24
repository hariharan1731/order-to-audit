# Interview Q&A (explain in your own words)

**Why integer paise, not double?** Binary floating point cannot store 0.1 exactly, so sums drift. Integers are exact; rates needing decimals use `BigDecimal` / `NUMERIC`.

**Why are stock movements append-only?** Audit trust. A trigger rejects UPDATE/DELETE; a mistake is fixed by a reversal row that points at the original, so history stays visible.

**How is "soft reservation" done?** Not stored. Available = on hand − approved orders not yet dispatched, computed when needed, so it cannot disagree with reality.

**What is `@Transactional` doing in dispatch?** Stock-out rows, order status and the audit row commit together or not at all. If stock is short, an exception rolls everything back.

**Why `Propagation.MANDATORY` on the audit service?** It forces auditing to happen inside the business transaction: no business action without audit, no audit for a failed action.

**How do you stop two people selling the last packets?** The stock balance row is locked (`FOR UPDATE`) inside the transaction, so the second dispatch waits, then sees the reduced balance and fails.

**How are gapless invoice numbers guaranteed?** A sequence row per financial year is locked, incremented and used in the same transaction as the invoice; a rollback also rolls the number back.

**CGST/SGST vs IGST?** Same state as the factory (Tamil Nadu) → tax splits equally into CGST + SGST; other state → IGST. ₹7,500 × 5% = ₹375 → 187.50 + 187.50.

**Explain weighted-average cost.** After each purchase, new average = total value ÷ total quantity. Flour: (100 kg×₹42 + 60 kg×₹45) ÷ 160 = ₹43.125/kg.

**Why is tolerance a strict `>`?** Spec says flag only when variance exceeds 3%; exactly 3% is acceptable.

**What does Spring Security do on a request?** A filter chain checks the session, applies URL rules top-down (first match wins), checks CSRF on POST, and denies by default.

**Why BCrypt?** Slow salted hash: stolen hashes are expensive to crack. `{bcrypt}` prefix lets the encoder upgrade algorithms later.

**Why not delete a wrong invoice?** It is a legal document and its number is already used. A credit note cancels it and returns stock, leaving both records.

**Why Flyway, and `ddl-auto: validate`?** Schema changes are versioned SQL under source control; Hibernate only checks Java matches the database, it never alters it.

**What would you build next?** Offline order entry for salesmen, FEFO batch tracking, low-stock alerts, partial credit notes, screens for items/recipes.

**What are the limits of your tests?** They prove the official benchmark and the rules; they don't cover load, real GST filing formats, or mobile browsers.

## After the self-review (what we fixed and why)

**Two people click Approve on the same order at once. What happens?** The order row is locked (`SELECT ... FOR UPDATE`) while its status changes, so the second person waits, then sees the new status and is refused. A test with real threads proves it.

**How do you undo a payment that bounced?** You never edit or delete it. A new `payment_reversal` row points at the payment (one reversal per payment, enforced by a UNIQUE constraint). Outstanding is worked out as invoice total minus payments that have no reversal, so the invoice simply owes the money again and the history keeps both rows.

**Why can a payment not be dated tomorrow?** A future date would put money in a report before it exists. The service refuses future payment, reversal, purchase and production dates, and a payment dated before its invoice. Invoice dates are not limited, because the financial-year numbering test deliberately raises invoices dated in the next year.

**Ageing "as of" last month: why did it need fixing?** It subtracted every payment ever made, including ones received after that date. Now a payment counts from the day it was received, and a reversal only cancels it from the day it was made, so an old report always shows the books as they stood then.

**How do you stop password guessing?** After 5 wrong passwords for a username within 15 minutes, that username is locked for 15 minutes, even for the right password. The count is kept in memory, resets on a good sign-in, and the screen says the same "wrong username or password" so an attacker cannot tell the lock is on. The audit log records "account is locked".

**A user is disabled while logged in: do they stay in?** No. A filter checks the account on every request and signs out a disabled user on their next click.

**You can edit a customer's state. Does that change old invoices?** No. An invoice stores its own copy of the customer name, GSTIN and state when it is raised, so editing the customer only affects invoices raised from then on. Switching a customer off keeps all history and only stops new orders.

**Why do credit notes have their own rows in the GST summary?** Netting them into the B2B and B2C rows made the sales rows look smaller than what was invoiced. GSTR-1 lists credit notes separately, so the report does too; the TOTAL row is still the net figure for GSTR-3B.

**A test failed because of a flush. What was that?** The reports read with plain SQL, but inside one test transaction JPA had not yet written the change. The fix was in the test (flush first), not the app, because in the real app each request is its own transaction.

**Overheads were wrong on a posted run. Why can't you just edit them?** The run's cost per packet is already in the stock ledger as the cost of the finished goods, and ledger rows are append-only. Editing the overhead would make the run disagree with its own stock movement. So you reverse the run (new reversal rows) and record it again with the right overheads. Nothing is deleted, and the audit log shows both steps.

**What is the pouch check?** Every packet made, good or damaged, used one pouch. So the pouches recorded as used must be at least good packets plus damaged pouches. For the benchmark that is 188 + 6 = 194 pouches, exactly what the official run records. If someone types fewer, the run is refused with a message.

**Where does the seller's name and state on the invoice come from?** From `app.seller.*` in application.yml (or the APP_SELLER_NAME, APP_SELLER_GSTIN and APP_SELLER_STATE environment variables). The demo values are Tamil Nadu (33), and a real business just sets its own.
