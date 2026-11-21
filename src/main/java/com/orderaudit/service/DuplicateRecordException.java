package com.orderaudit.service;

/** Generic "this name is already taken" error for recipes and pack configurations. */
public class DuplicateRecordException extends RuntimeException {
    public DuplicateRecordException(String what, String value) {
        super(what + " '" + value + "' already exists");
    }
}
