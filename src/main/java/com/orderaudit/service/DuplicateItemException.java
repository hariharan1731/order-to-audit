package com.orderaudit.service;

/** Thrown when an item with the same name (ignoring case) already exists. */
public class DuplicateItemException extends RuntimeException {
    public DuplicateItemException(String name) {
        super("An item named '" + name + "' already exists");
    }
}
