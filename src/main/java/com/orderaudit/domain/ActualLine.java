package com.orderaudit.domain;

/** What was REALLY used of one item in a run, in base units. Items not listed are taken as "used as planned". */
public record ActualLine(long itemId, long actualQuantity) {
}
