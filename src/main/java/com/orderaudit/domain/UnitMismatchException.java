package com.orderaudit.domain;

/** Thrown when someone tries to convert between unrelated units, such as kg to litres. */
public class UnitMismatchException extends RuntimeException {
    public UnitMismatchException(String message) {
        super(message);
    }
}
