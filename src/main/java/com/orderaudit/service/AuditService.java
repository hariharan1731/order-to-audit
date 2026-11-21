package com.orderaudit.service;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit log. MANDATORY propagation: an audit row can only be written inside the
 * business transaction it describes, so the action and its audit row either both happen or neither does.
 * The database blocks UPDATE and DELETE on audit_log, so history cannot be edited afterwards.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String entityType, Long entityId, String details) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("An audit entry needs an actor");
        }
        if (action == null || action.isBlank() || entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("An audit entry needs an action and an entity type");
        }
        String shortDetails = details == null ? null : details.length() > 500 ? details.substring(0, 500) : details;
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) VALUES (?, ?, ?, ?, ?)",
                actor.trim(), action, entityType, entityId, shortDetails);
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<AuditEntry> recent(int limit) {
        return jdbc.query("SELECT id, at, actor, action, entity_type, entity_id, details FROM audit_log "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, i) -> new AuditEntry(rs.getLong("id"), rs.getObject("at", OffsetDateTime.class),
                        rs.getString("actor"), rs.getString("action"), rs.getString("entity_type"),
                        (Long) rs.getObject("entity_id"), rs.getString("details")),
                Math.max(1, Math.min(limit, 1000)));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> forEntity(String entityType, long entityId) {
        return jdbc.query("SELECT id, at, actor, action, entity_type, entity_id, details FROM audit_log "
                        + "WHERE entity_type = ? AND entity_id = ? ORDER BY id",
                (rs, i) -> new AuditEntry(rs.getLong("id"), rs.getObject("at", OffsetDateTime.class),
                        rs.getString("actor"), rs.getString("action"), rs.getString("entity_type"),
                        (Long) rs.getObject("entity_id"), rs.getString("details")),
                entityType, entityId);
    }
}
