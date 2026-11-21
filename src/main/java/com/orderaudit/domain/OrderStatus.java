package com.orderaudit.domain;

/** SUBMITTED -> APPROVED -> INVOICED -> DISPATCHED, or SUBMITTED -> REJECTED. */
public enum OrderStatus {
    SUBMITTED, APPROVED, INVOICED, DISPATCHED, REJECTED
}
