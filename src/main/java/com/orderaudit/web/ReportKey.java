package com.orderaudit.web;

import java.util.Arrays;
import java.util.Optional;

/** The twelve reports. The slug is what appears in the web address. */
public enum ReportKey {
    SALES_REGISTER("sales-register", "Sales register", Kind.PERIOD),
    CUSTOMER_SALES("customer-sales", "Customer-wise sales", Kind.PERIOD),
    PRODUCT_SALES("product-sales", "Product-wise sales", Kind.PERIOD),
    GST_SUMMARY("gst-summary", "GST summary (GSTR-1 / 3B)", Kind.PERIOD),
    PURCHASE_REGISTER("purchase-register", "Purchase register", Kind.PERIOD),
    STOCK_SUMMARY("stock-summary", "Stock summary", Kind.PERIOD),
    STOCK_LEDGER("stock-ledger", "Stock ledger of one item", Kind.ITEM_PERIOD),
    PRODUCTION_YIELD("production-yield", "Production yield and wastage", Kind.PERIOD),
    PRODUCTION_CONSUMPTION("production-consumption", "Planned vs actual consumption", Kind.PERIOD),
    COST_PER_PACKET("cost-per-packet", "Cost per packet and margin", Kind.PERIOD),
    COLLECTIONS("collections", "Payments and collections", Kind.PERIOD),
    OUTSTANDING("outstanding", "Outstanding receivables with ageing", Kind.AS_OF);

    /** What the report needs from the user: a from-to period, one date, or an item plus a period. */
    public enum Kind { PERIOD, AS_OF, ITEM_PERIOD }

    private final String slug;
    private final String title;
    private final Kind kind;

    ReportKey(String slug, String title, Kind kind) {
        this.slug = slug;
        this.title = title;
        this.kind = kind;
    }

    public String slug() { return slug; }
    public String title() { return title; }
    public Kind kind() { return kind; }

    public static Optional<ReportKey> fromSlug(String slug) {
        return Arrays.stream(values()).filter(k -> k.slug.equals(slug)).findFirst();
    }
}
