package com.orderaudit.domain;

/** One raw material in a recipe: item id and quantity per ONE batch, in base units. */
public record RecipeLineSpec(long itemId, long quantityBase) {
}
