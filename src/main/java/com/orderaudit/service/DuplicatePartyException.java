package com.orderaudit.service;

/** Thrown when a customer or supplier name or GSTIN is already in use. */
public class DuplicatePartyException extends RuntimeException {
    public DuplicatePartyException(String what, String value) {
        super(what + " '" + value + "' already exists");
    }
}
