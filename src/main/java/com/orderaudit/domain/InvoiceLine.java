package com.orderaudit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "invoice_line")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "hsn_code")
    private String hsnCode;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "unit_price_paise", nullable = false)
    private long unitPricePaise;

    @Column(name = "taxable_paise", nullable = false)
    private long taxablePaise;

    @Column(name = "gst_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePercent;

    @Column(name = "cgst_paise", nullable = false)
    private long cgstPaise;

    @Column(name = "sgst_paise", nullable = false)
    private long sgstPaise;

    @Column(name = "igst_paise", nullable = false)
    private long igstPaise;

    protected InvoiceLine() {
    }

    public InvoiceLine(Long invoiceId, Item item, long quantity, long unitPricePaise, GstBreakdown tax) {
        this.invoiceId = invoiceId;
        this.itemId = item.getId();
        this.itemName = item.getName();
        this.hsnCode = item.getHsnCode();
        this.quantity = quantity;
        this.unitPricePaise = unitPricePaise;
        this.taxablePaise = tax.taxableValue().paise();
        this.gstRatePercent = item.getGstRatePercent();
        this.cgstPaise = tax.cgst().paise();
        this.sgstPaise = tax.sgst().paise();
        this.igstPaise = tax.igst().paise();
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public Long getItemId() { return itemId; }
    public String getItemName() { return itemName; }
    public String getHsnCode() { return hsnCode; }
    public long getQuantity() { return quantity; }
    public long getUnitPricePaise() { return unitPricePaise; }
    public long getTaxablePaise() { return taxablePaise; }
    public BigDecimal getGstRatePercent() { return gstRatePercent; }
    public long getCgstPaise() { return cgstPaise; }
    public long getSgstPaise() { return sgstPaise; }
    public long getIgstPaise() { return igstPaise; }
}
