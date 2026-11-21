package com.orderaudit.service;

import java.time.OffsetDateTime;

/** One row read back from the audit log. */
public record AuditEntry(long id, OffsetDateTime at, String actor, String action, String entityType,
                         Long entityId, String details) {
}
