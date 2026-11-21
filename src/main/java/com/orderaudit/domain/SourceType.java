package com.orderaudit.domain;

/** The kind of document a stock movement came from. Together with source_id it names the document. */
public enum SourceType {
    STOCK_ADJUSTMENT,
    PURCHASE,
    PRODUCTION_RUN,
    DISPATCH,
    SALES_RETURN
}
