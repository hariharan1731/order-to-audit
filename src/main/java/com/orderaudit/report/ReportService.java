package com.orderaudit.report;

import com.orderaudit.domain.AgeingRow;
import com.orderaudit.domain.Unit;
import com.orderaudit.service.CostMath;
import com.orderaudit.service.ReceivablesService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds every report as a ReportTable by reading the posted records (read-only SQL).
 * Reports never change anything and never calculate money with floating point: amounts are whole paise.
 * Dates use Indian time (stock movements carry a timestamp, so the date is taken in Asia/Kolkata).
 */
@Service
public class ReportService {

    private static final String MOVEMENT_DATE = "(posted_at AT TIME ZONE 'Asia/Kolkata')::date";

    private final JdbcTemplate jdbc;
    private final ReceivablesService receivables;

    public ReportService(JdbcTemplate jdbc, ReceivablesService receivables) {
        this.jdbc = jdbc;
        this.receivables = receivables;
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    private static String unitLabel(String baseUnit) {
        return QuantityFormat.baseUnitLabel(Unit.valueOf(baseUnit));
    }

    // ------------------------------------------------------------------ sales

    /**
     * Every sales document: each invoice (also a cancelled one, it was a real tax document on its date)
     * and each credit note shown with NEGATIVE amounts on the credit note's date. So a cancelled invoice and
     * its credit note cancel each other out in any period that contains both.
     */
    private static final String SALES_DOCUMENTS =
            "SELECT i.invoice_date AS doc_date, i.invoice_number AS doc_no, 0 AS kind, i.id AS sort_id, i.customer_id, "
                    + "i.customer_name, i.customer_gstin, i.customer_state, i.taxable_paise, i.cgst_paise, "
                    + "i.sgst_paise, i.igst_paise, i.total_paise FROM invoice i "
                    + "UNION ALL "
                    + "SELECT c.credit_date, c.credit_note_number, 1, c.id, i.customer_id, i.customer_name, "
                    + "i.customer_gstin, i.customer_state, -c.taxable_paise, -c.cgst_paise, -c.sgst_paise, "
                    + "-c.igst_paise, -c.total_paise FROM credit_note c JOIN invoice i ON i.id = c.invoice_id";

    /** The same idea at line level (for product-wise sales and the GST summary by rate). */
    private static final String SALES_LINES =
            "SELECT i.invoice_date AS doc_date, i.customer_gstin, l.item_id, l.item_name, l.hsn_code, l.quantity, "
                    + "l.gst_rate_percent, l.taxable_paise, l.cgst_paise, l.sgst_paise, l.igst_paise "
                    + "FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id "
                    + "UNION ALL "
                    + "SELECT c.credit_date, i.customer_gstin, l.item_id, l.item_name, l.hsn_code, -l.quantity, "
                    + "l.gst_rate_percent, -l.taxable_paise, -l.cgst_paise, -l.sgst_paise, -l.igst_paise "
                    + "FROM invoice_line l JOIN credit_note c ON c.invoice_id = l.invoice_id "
                    + "JOIN invoice i ON i.id = l.invoice_id";

    /** Every issued invoice in the period, with its tax split. */
    @Transactional(readOnly = true)
    public ReportTable salesRegister(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT doc_date AS invoice_date, doc_no AS invoice_number, customer_name, customer_gstin, "
                        + "customer_state, taxable_paise, cgst_paise, sgst_paise, igst_paise, total_paise "
                        + "FROM (" + SALES_DOCUMENTS + ") d WHERE doc_date BETWEEN ? AND ? "
                        + "ORDER BY doc_date, kind, sort_id",
                (rs, i) -> row(rs.getObject("invoice_date", LocalDate.class), rs.getString("invoice_number"),
                        rs.getString("customer_name"), rs.getString("customer_gstin"), rs.getString("customer_state"),
                        rs.getLong("taxable_paise"), rs.getLong("cgst_paise"), rs.getLong("sgst_paise"),
                        rs.getLong("igst_paise"), rs.getLong("total_paise")),
                p.from(), p.to());
        return new ReportTable("Sales register", p.label(),
                List.of(ReportColumn.date("Date"), ReportColumn.text("Invoice no"), ReportColumn.text("Customer"),
                        ReportColumn.text("GSTIN"), ReportColumn.text("State"), ReportColumn.money("Taxable (Rs)"),
                        ReportColumn.money("CGST (Rs)"), ReportColumn.money("SGST (Rs)"),
                        ReportColumn.money("IGST (Rs)"), ReportColumn.money("Total (Rs)")),
                rows, row(null, null, "TOTAL", null, null, sum(rows, 5), sum(rows, 6), sum(rows, 7), sum(rows, 8), sum(rows, 9)));
    }

    @Transactional(readOnly = true)
    public ReportTable customerWiseSales(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT customer_name, count(*) FILTER (WHERE total_paise > 0) AS invoices, "
                        + "sum(taxable_paise) AS taxable, sum(cgst_paise + sgst_paise + igst_paise) AS tax, "
                        + "sum(total_paise) AS total "
                        + "FROM (" + SALES_DOCUMENTS + ") d WHERE doc_date BETWEEN ? AND ? "
                        + "GROUP BY customer_id, customer_name ORDER BY customer_name",
                (rs, i) -> row(rs.getString("customer_name"), rs.getLong("invoices"), rs.getLong("taxable"),
                        rs.getLong("tax"), rs.getLong("total")),
                p.from(), p.to());
        return new ReportTable("Customer-wise sales", p.label(),
                List.of(ReportColumn.text("Customer"), ReportColumn.quantity("Invoices"),
                        ReportColumn.money("Taxable (Rs)"), ReportColumn.money("Tax (Rs)"), ReportColumn.money("Total (Rs)")),
                rows, row("TOTAL", sum(rows, 1), sum(rows, 2), sum(rows, 3), sum(rows, 4)));
    }

    @Transactional(readOnly = true)
    public ReportTable productWiseSales(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT item_name, hsn_code, sum(quantity) AS quantity, sum(taxable_paise) AS taxable, "
                        + "sum(cgst_paise + sgst_paise + igst_paise) AS tax "
                        + "FROM (" + SALES_LINES + ") d WHERE doc_date BETWEEN ? AND ? "
                        + "GROUP BY item_id, item_name, hsn_code ORDER BY item_name",
                (rs, i) -> {
                    long taxable = rs.getLong("taxable");
                    long tax = rs.getLong("tax");
                    return row(rs.getString("item_name"), rs.getString("hsn_code"), rs.getLong("quantity"),
                            taxable, tax, taxable + tax);
                },
                p.from(), p.to());
        return new ReportTable("Product-wise sales", p.label(),
                List.of(ReportColumn.text("Product"), ReportColumn.text("HSN"), ReportColumn.quantity("Quantity (packets)"),
                        ReportColumn.money("Taxable (Rs)"), ReportColumn.money("Tax (Rs)"), ReportColumn.money("Total (Rs)")),
                rows, row("TOTAL", null, sum(rows, 2), sum(rows, 3), sum(rows, 4), sum(rows, 5)));
    }

    /**
     * Outward supplies by customer type (B2B = customer has a GSTIN, B2C = no GSTIN) and GST rate.
     * Laid out to map onto GSTR-1 (B2B / B2C tables) and GSTR-3B table 3.1(a): the TOTAL row is the
     * outward taxable value and tax to report in 3B.
     */
    @Transactional(readOnly = true)
    public ReportTable gstSummary(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT CASE WHEN customer_gstin IS NOT NULL THEN 'B2B' ELSE 'B2C' END AS kind, "
                        + "gst_rate_percent AS rate, sum(taxable_paise) AS taxable, sum(cgst_paise) AS cgst, "
                        + "sum(sgst_paise) AS sgst, sum(igst_paise) AS igst "
                        + "FROM (" + SALES_LINES + ") d WHERE doc_date BETWEEN ? AND ? "
                        + "GROUP BY 1, gst_rate_percent ORDER BY 1, gst_rate_percent",
                (rs, i) -> {
                    long taxable = rs.getLong("taxable");
                    long cgst = rs.getLong("cgst");
                    long sgst = rs.getLong("sgst");
                    long igst = rs.getLong("igst");
                    return row(rs.getString("kind"), rs.getBigDecimal("rate"), taxable, cgst, sgst, igst,
                            cgst + sgst + igst, taxable + cgst + sgst + igst);
                },
                p.from(), p.to());
        return new ReportTable("GST summary of outward supplies", p.label() + " (GSTR-1 / GSTR-3B 3.1(a))",
                List.of(ReportColumn.text("Supply type"), ReportColumn.percent("GST rate %"),
                        ReportColumn.money("Taxable value (Rs)"), ReportColumn.money("CGST (Rs)"),
                        ReportColumn.money("SGST (Rs)"), ReportColumn.money("IGST (Rs)"),
                        ReportColumn.money("Total tax (Rs)"), ReportColumn.money("Invoice value (Rs)")),
                rows, row("TOTAL", null, sum(rows, 2), sum(rows, 3), sum(rows, 4), sum(rows, 5), sum(rows, 6), sum(rows, 7)));
    }

    // -------------------------------------------------------------- purchases

    @Transactional(readOnly = true)
    public ReportTable purchaseRegister(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT p.purchase_date, s.name AS supplier, p.reference, it.name AS item, it.base_unit, "
                        + "l.quantity_base, l.unit_cost, round(l.quantity_base * l.unit_cost) AS amount "
                        + "FROM purchase p JOIN purchase_line l ON l.purchase_id = p.id "
                        + "JOIN supplier s ON s.id = p.supplier_id JOIN item it ON it.id = l.item_id "
                        + "WHERE p.purchase_date BETWEEN ? AND ? ORDER BY p.purchase_date, p.id, it.name",
                (rs, i) -> row(rs.getObject("purchase_date", LocalDate.class), rs.getString("supplier"),
                        rs.getString("reference"), rs.getString("item"), rs.getLong("quantity_base"),
                        unitLabel(rs.getString("base_unit")), rs.getBigDecimal("unit_cost"), rs.getLong("amount")),
                p.from(), p.to());
        return new ReportTable("Purchase register", p.label(),
                List.of(ReportColumn.date("Date"), ReportColumn.text("Supplier"), ReportColumn.text("Reference"),
                        ReportColumn.text("Item"), ReportColumn.quantity("Quantity"), ReportColumn.text("Unit"),
                        ReportColumn.decimal4("Rate (paise per unit)"), ReportColumn.money("Amount (Rs)")),
                rows, row(null, "TOTAL", null, null, null, null, null, sum(rows, 7)));
    }

    // ------------------------------------------------------------------ stock

    /**
     * Opening + receipts - issues +/- adjustments = closing, per item.
     * Opening = stock brought forward: the OPENING-stock entries plus everything before the period.
     * Receipts = purchases, production output, returns. Issues = production issues and sales (shown positive).
     * Adjustments = manual corrections and reversals (signed).
     */
    @Transactional(readOnly = true)
    public ReportTable stockSummary(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT it.name, it.item_type, it.base_unit, "
                        + "COALESCE(SUM(m.qty_signed) FILTER (WHERE m.movement_type = 'OPENING' OR m.d < ?), 0) AS opening, "
                        + "COALESCE(SUM(m.qty_signed) FILTER (WHERE m.movement_type IN ('PURCHASE','PROD_OUTPUT','RETURN') "
                        + "AND m.d BETWEEN ? AND ?), 0) AS receipts, "
                        + "COALESCE(SUM(m.qty_signed) FILTER (WHERE m.movement_type IN ('PROD_ISSUE','SALE') "
                        + "AND m.d BETWEEN ? AND ?), 0) AS issues, "
                        + "COALESCE(SUM(m.qty_signed) FILTER (WHERE m.movement_type IN ('ADJUST','REVERSAL') "
                        + "AND m.d BETWEEN ? AND ?), 0) AS adjustments "
                        + "FROM item it JOIN (SELECT item_id, movement_type, qty_signed, " + MOVEMENT_DATE + " AS d "
                        + "FROM stock_movement) m ON m.item_id = it.id "
                        + "GROUP BY it.id, it.name, it.item_type, it.base_unit "
                        + "ORDER BY CASE it.item_type WHEN 'RAW' THEN 1 WHEN 'PACKAGING' THEN 2 ELSE 3 END, it.name",
                (rs, i) -> {
                    long opening = rs.getLong("opening");
                    long receipts = rs.getLong("receipts");
                    long issues = -rs.getLong("issues");
                    long adjustments = rs.getLong("adjustments");
                    long closing = opening + receipts - issues + adjustments;
                    Unit unit = Unit.valueOf(rs.getString("base_unit"));
                    return row(rs.getString("name"), rs.getString("item_type"), QuantityFormat.baseUnitLabel(unit),
                            opening, receipts, issues, adjustments, closing, QuantityFormat.display(closing, unit));
                },
                p.from(), p.from(), p.to(), p.from(), p.to(), p.from(), p.to());
        return new ReportTable("Stock summary", p.label(),
                List.of(ReportColumn.text("Item"), ReportColumn.text("Type"), ReportColumn.text("Unit"),
                        ReportColumn.quantity("Opening"), ReportColumn.quantity("Receipts"),
                        ReportColumn.quantity("Issues / sales"), ReportColumn.quantity("Adjustments"),
                        ReportColumn.quantity("Closing"), ReportColumn.text("Closing (readable)")),
                rows, null);
    }

    /** Every movement of one item in the period with its source document and a running balance. */
    @Transactional(readOnly = true)
    public ReportTable stockLedger(long itemId, ReportPeriod p) {
        List<List<String>> item = jdbc.query("SELECT name, base_unit FROM item WHERE id = ?",
                (rs, i) -> List.of(rs.getString("name"), rs.getString("base_unit")), itemId);
        if (item.isEmpty()) {
            throw new IllegalArgumentException("Item " + itemId + " does not exist");
        }
        Long openingOrNull = jdbc.queryForObject("SELECT COALESCE(SUM(qty_signed), 0) FROM stock_movement WHERE item_id = ? AND "
                + MOVEMENT_DATE + " < ?", Long.class, itemId, p.from());
        long opening = openingOrNull == null ? 0 : openingOrNull;

        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(p.from().toString(), "Brought forward", null, null, null, null, opening, null, null));
        long[] running = {opening};
        long[] totalIn = {0};
        long[] totalOut = {0};
        jdbc.query("SELECT to_char(posted_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI') AS at, movement_type, "
                        + "source_type, source_id, reverses_id, qty_signed, unit_cost, posted_by "
                        + "FROM stock_movement WHERE item_id = ? AND " + MOVEMENT_DATE + " BETWEEN ? AND ? ORDER BY id",
                rs -> {
                    long qty = rs.getLong("qty_signed");
                    long in = Math.max(qty, 0);
                    long out = Math.max(-qty, 0);
                    running[0] += qty;
                    totalIn[0] += in;
                    totalOut[0] += out;
                    long reverses = rs.getLong("reverses_id");
                    boolean isReversal = !rs.wasNull();   // must be asked right after reading that column
                    rows.add(row(rs.getString("at"), rs.getString("movement_type"),
                            rs.getString("source_type") + " #" + rs.getLong("source_id"),
                            isReversal ? "reverses #" + reverses : null, in, out, running[0],
                            rs.getBigDecimal("unit_cost"), rs.getString("posted_by")));
                },
                itemId, p.from(), p.to());
        return new ReportTable("Stock ledger - " + item.get(0).get(0),
                p.label() + " (quantities in " + unitLabel(item.get(0).get(1)) + ")",
                List.of(ReportColumn.text("Date and time"), ReportColumn.text("Movement"), ReportColumn.text("Source document"),
                        ReportColumn.text("Reversal of"), ReportColumn.quantity("In"), ReportColumn.quantity("Out"),
                        ReportColumn.quantity("Balance"), ReportColumn.decimal4("Unit cost (paise)"), ReportColumn.text("Posted by")),
                rows, row(null, "TOTAL / closing", null, null, totalIn[0], totalOut[0], running[0], null, null));
    }

    // ------------------------------------------------------------- production

    @Transactional(readOnly = true)
    public ReportTable productionYield(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT r.id, r.run_date, it.name AS product, r.expected_packets, r.actual_good_packets, "
                        + "r.yield_variance_pct, r.yield_flagged, r.damaged_pouches, r.tolerance_pct, r.status "
                        + "FROM production_run r JOIN pack_config pc ON pc.id = r.pack_config_id "
                        + "JOIN item it ON it.id = pc.finished_item_id "
                        + "WHERE r.run_date BETWEEN ? AND ? ORDER BY r.run_date, r.id",
                (rs, i) -> row("Run #" + rs.getLong("id"), rs.getObject("run_date", LocalDate.class),
                        rs.getString("product"), rs.getLong("expected_packets"), rs.getLong("actual_good_packets"),
                        rs.getBigDecimal("yield_variance_pct"), rs.getBoolean("yield_flagged") ? "FLAGGED" : "ok",
                        rs.getLong("damaged_pouches"), rs.getBigDecimal("tolerance_pct"), rs.getString("status")),
                p.from(), p.to());
        return new ReportTable("Production yield and wastage", p.label(),
                List.of(ReportColumn.text("Run"), ReportColumn.date("Date"), ReportColumn.text("Product"),
                        ReportColumn.quantity("Expected packets"), ReportColumn.quantity("Good packets"),
                        ReportColumn.percent("Yield variance %"), ReportColumn.text("Flag"),
                        ReportColumn.quantity("Damaged pouches"), ReportColumn.percent("Tolerance %"),
                        ReportColumn.text("Status")),
                rows, null);
    }

    @Transactional(readOnly = true)
    public ReportTable productionConsumption(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT r.id, r.run_date, fi.name AS product, ri.name AS item, ri.base_unit, rl.source, "
                        + "rl.planned_qty, rl.actual_qty, rl.variance_pct, rl.flagged "
                        + "FROM run_line rl JOIN production_run r ON r.id = rl.run_id "
                        + "JOIN pack_config pc ON pc.id = r.pack_config_id JOIN item fi ON fi.id = pc.finished_item_id "
                        + "JOIN item ri ON ri.id = rl.item_id "
                        + "WHERE r.run_date BETWEEN ? AND ? ORDER BY r.run_date, r.id, ri.name",
                (rs, i) -> row("Run #" + rs.getLong("id"), rs.getObject("run_date", LocalDate.class),
                        rs.getString("product"), rs.getString("item"), unitLabel(rs.getString("base_unit")),
                        rs.getLong("planned_qty"), rs.getLong("actual_qty"), rs.getBigDecimal("variance_pct"),
                        rs.getBoolean("flagged") ? "FLAGGED" : "ok"),
                p.from(), p.to());
        return new ReportTable("Production planned vs actual consumption", p.label(),
                List.of(ReportColumn.text("Run"), ReportColumn.date("Date"), ReportColumn.text("Product"),
                        ReportColumn.text("Item"), ReportColumn.text("Unit"), ReportColumn.quantity("Planned"),
                        ReportColumn.quantity("Actual"), ReportColumn.percent("Variance %"), ReportColumn.text("Flag")),
                rows, null);
    }

    /**
     * Cost per packet of each posted run: (materials at weighted average cost + overheads) / good packets,
     * and the margin against the current selling price.
     */
    @Transactional(readOnly = true)
    public ReportTable costPerPacket(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT r.id, r.run_date, it.name AS product, r.actual_good_packets, r.total_material_paise, "
                        + "r.total_overhead_paise, r.total_cost_paise, r.cost_per_packet, ip.price_paise "
                        + "FROM production_run r JOIN pack_config pc ON pc.id = r.pack_config_id "
                        + "JOIN item it ON it.id = pc.finished_item_id LEFT JOIN item_price ip ON ip.item_id = it.id "
                        + "WHERE r.status = 'POSTED' AND r.run_date BETWEEN ? AND ? ORDER BY r.run_date, r.id",
                (rs, i) -> {
                    BigDecimal cost = rs.getBigDecimal("cost_per_packet");
                    long priceRaw = rs.getLong("price_paise");
                    Long price = rs.wasNull() ? null : priceRaw;
                    Long costRupees = cost == null ? null : CostMath.roundToPaise(cost);
                    Long margin = null;
                    BigDecimal marginPct = null;
                    if (cost != null && price != null) {
                        BigDecimal marginExact = BigDecimal.valueOf(price).subtract(cost);
                        margin = CostMath.roundToPaise(marginExact);
                        if (price > 0) {
                            marginPct = marginExact.multiply(BigDecimal.valueOf(100))
                                    .divide(BigDecimal.valueOf(price), 2, RoundingMode.HALF_UP);
                        }
                    }
                    return row("Run #" + rs.getLong("id"), rs.getObject("run_date", LocalDate.class),
                            rs.getString("product"), rs.getLong("actual_good_packets"),
                            rs.getLong("total_material_paise"), rs.getLong("total_overhead_paise"),
                            rs.getLong("total_cost_paise"), cost, costRupees, price, margin, marginPct);
                },
                p.from(), p.to());
        return new ReportTable("Cost per packet and margin",
                p.label() + " - weighted average cost of materials + run overheads, divided by good packets",
                List.of(ReportColumn.text("Run"), ReportColumn.date("Date"), ReportColumn.text("Product"),
                        ReportColumn.quantity("Good packets"), ReportColumn.money("Materials (Rs)"),
                        ReportColumn.money("Overheads (Rs)"), ReportColumn.money("Total cost (Rs)"),
                        ReportColumn.decimal4("Cost per packet (paise)"), ReportColumn.money("Cost per packet (Rs)"),
                        ReportColumn.money("Selling price (Rs)"), ReportColumn.money("Margin per packet (Rs)"),
                        ReportColumn.percent("Margin %")),
                rows, row("TOTAL", null, null, sum(rows, 3), sum(rows, 4), sum(rows, 5), sum(rows, 6),
                        null, null, null, null, null));
    }

    // ----------------------------------------------------------------- money in

    @Transactional(readOnly = true)
    public ReportTable collections(ReportPeriod p) {
        List<List<Object>> rows = jdbc.query(
                "SELECT p.payment_date, c.name AS customer, p.method, p.reference, "
                        + "string_agg(i.invoice_number, ', ' ORDER BY i.invoice_number) AS invoices, p.amount_paise "
                        + "FROM payment p JOIN customer c ON c.id = p.customer_id "
                        + "JOIN payment_allocation a ON a.payment_id = p.id JOIN invoice i ON i.id = a.invoice_id "
                        + "WHERE p.payment_date BETWEEN ? AND ? GROUP BY p.id, c.name ORDER BY p.payment_date, p.id",
                (rs, i) -> row(rs.getObject("payment_date", LocalDate.class), rs.getString("customer"),
                        rs.getString("method"), rs.getString("reference"), rs.getString("invoices"),
                        rs.getLong("amount_paise")),
                p.from(), p.to());
        return new ReportTable("Payments and collections", p.label(),
                List.of(ReportColumn.date("Date"), ReportColumn.text("Customer"), ReportColumn.text("Method"),
                        ReportColumn.text("Reference"), ReportColumn.text("Against invoices"),
                        ReportColumn.money("Amount (Rs)")),
                rows, row(null, "TOTAL", null, null, null, sum(rows, 5)));
    }

    /** What each customer owes as of a date, split by the age of the invoices. */
    @Transactional(readOnly = true)
    public ReportTable outstandingAndAgeing(LocalDate asOf) {
        List<AgeingRow> ageing = receivables.ageing(asOf);
        List<List<Object>> rows = new ArrayList<>();
        for (AgeingRow a : ageing) {
            rows.add(row(a.customerName(), a.days0to30(), a.days31to60(), a.days61to90(), a.over90(), a.total()));
        }
        return new ReportTable("Outstanding receivables with ageing", "As of " + asOf
                + " (age counted from the invoice date)",
                List.of(ReportColumn.text("Customer"), ReportColumn.money("0-30 days (Rs)"),
                        ReportColumn.money("31-60 days (Rs)"), ReportColumn.money("61-90 days (Rs)"),
                        ReportColumn.money("Over 90 days (Rs)"), ReportColumn.money("Total outstanding (Rs)")),
                rows, row("TOTAL", sum(rows, 1), sum(rows, 2), sum(rows, 3), sum(rows, 4), sum(rows, 5)));
    }

    /** Sum of one numeric column over the rows (all cells in that column are Long). */
    private static Long sum(List<List<Object>> rows, int column) {
        long total = 0;
        for (List<Object> row : rows) {
            Object cell = row.get(column);
            if (cell != null) {
                total += (Long) cell;
            }
        }
        return total;
    }
}
