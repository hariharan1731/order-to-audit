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
