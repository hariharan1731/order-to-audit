package com.orderaudit.web;

import com.orderaudit.domain.OrderStatus;
import java.time.LocalDate;

/**
 * What the user typed into the search and filter bar. Kept as text so the form can show it back to them
 * exactly as they left it, with the parsing done here in one place.
 */
public record OrderFilter(String q, String status, LocalDate from, LocalDate to, String sort) {

    /** The chosen status, or null for "any status" (also null if the text is not a real status). */
    public OrderStatus statusValue() {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when the user is actually searching or filtering, so the screen shows results instead of the queues. */
    public boolean active() {
        return (q != null && !q.isBlank()) || statusValue() != null || from != null || to != null;
    }

    public String sortOrDefault() {
        return sort == null || sort.isBlank() ? "newest" : sort;
    }
}
