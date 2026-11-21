package com.orderaudit.domain;

/**
 * The five roles. Spring Security stores them with a ROLE_ prefix (ROLE_ADMIN), but we only ever write
 * the plain name in code: hasRole("ADMIN") adds the prefix for us.
 */
public enum Role {
    /** Takes orders from the phone. Sees only the sales screens. */
    SALESMAN,
    /** Approves orders, raises invoices, dispatches, records payments, manages masters and users. */
    ADMIN,
    /** Records production runs and stock. */
    PRODUCTION_SUPERVISOR,
    /** Reads reports and receivables. */
    ACCOUNTANT,
    /** Sees everything. */
    OWNER
}
