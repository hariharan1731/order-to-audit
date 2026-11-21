package com.orderaudit.domain;

/** Tax split for one invoice line or a whole invoice. Intra-state uses cgst+sgst, inter-state uses igst. */
public record GstBreakdown(Money taxableValue, Money cgst, Money sgst, Money igst) {

    public static final GstBreakdown ZERO =
            new GstBreakdown(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

    public Money totalTax() {
        return cgst.plus(sgst).plus(igst);
    }

    public Money invoiceTotal() {
        return taxableValue.plus(totalTax());
    }

    /** Add two breakdowns, used to sum per-line GST into an invoice total. */
    public GstBreakdown plus(GstBreakdown other) {
        return new GstBreakdown(
                taxableValue.plus(other.taxableValue),
                cgst.plus(other.cgst),
                sgst.plus(other.sgst),
                igst.plus(other.igst));
    }
}
