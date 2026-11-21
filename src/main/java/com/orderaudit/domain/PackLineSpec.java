package com.orderaudit.domain;

/** One packaging line: item id, pieces, and whether it scales per packet or per carton. */
public record PackLineSpec(long itemId, long quantity, PackBasis basis) {
}
