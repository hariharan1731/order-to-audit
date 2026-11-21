package com.orderaudit.domain;

/** One overhead cost for a run (labour, gas, power...), in whole paise. */
public record OverheadInput(String description, long amountPaise) {
}
