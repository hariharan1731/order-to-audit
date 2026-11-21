package com.orderaudit.domain;

import java.math.BigDecimal;

/** Result of comparing actual with planned. percent is NULL when planned is 0 but actual is not. */
public record Variance(BigDecimal percent, boolean flagged) {
}
