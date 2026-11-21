package com.orderaudit.service;

import com.orderaudit.domain.AgeingRow;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.OrderStatus;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner's one-page summary. Every number comes from the same services and reports the other screens use. */
@Service
public class DashboardService {

    public record Dashboard(ReportPeriod period, long salesInclGstPaise, long taxableSalesPaise, long outstandingPaise,
                            List<AgeingRow> topDebtors, long ordersWaiting, long flaggedRuns, long finishedPackets,
                            String latestCostPerPacket, String latestMarginPct,
                            List<String> salesDays, List<java.math.BigDecimal> salesAmounts,
                            List<java.math.BigDecimal> ageingAmounts) {
    }

    private final ReportService reports;
    private final ReceivablesService receivables;
    private final OrderService orders;
    private final StockQueryService stockQuery;

    public DashboardService(ReportService reports, ReceivablesService receivables, OrderService orders,
                            StockQueryService stockQuery) {
        this.reports = reports;
        this.receivables = receivables;
        this.orders = orders;
        this.stockQuery = stockQuery;
    }

    @Transactional(readOnly = true)
    public Dashboard compute(ReportPeriod period) {
        ReportTable register = reports.salesRegister(period);
        long taxable = (Long) register.totals().get(5);
        long total = (Long) register.totals().get(9);

        List<AgeingRow> ageing = receivables.ageing(period.to());
        long outstanding = ageing.stream().mapToLong(AgeingRow::total).sum();
        List<AgeingRow> top = ageing.stream()
                .sorted((a, b) -> Long.compare(b.total(), a.total())).limit(5).toList();

        long flagged = reports.productionYield(period).rows().stream()
                .filter(r -> "FLAGGED".equals(r.get(6))).count();
        long finished = stockQuery.snapshot().stream().filter(r -> r.type() == ItemType.FINISHED)
                .mapToLong(StockQueryService.StockRow::onHand).sum();

        ReportTable cost = reports.costPerPacket(period);
        String costText = "-";
        String marginText = "-";
        if (!cost.rows().isEmpty()) {
            List<Object> last = cost.rows().get(cost.rows().size() - 1);
            if (last.get(8) != null) {
                costText = "Rs " + String.format(java.util.Locale.ENGLISH, "%,.2f", java.math.BigDecimal.valueOf((Long) last.get(8), 2));
            }
            if (last.get(11) != null) {
                marginText = last.get(11) + "%";
            }
        }
        // Chart data: sales per day (credit notes already negative in the register) and the four ageing buckets.
        java.util.Map<String, Long> perDay = new java.util.TreeMap<>();
        for (List<Object> r : register.rows()) {
            perDay.merge(String.valueOf(r.get(0)), (Long) r.get(9), Long::sum);
        }
        List<String> days = new java.util.ArrayList<>(perDay.keySet());
        List<java.math.BigDecimal> amounts = perDay.values().stream()
                .map(p -> java.math.BigDecimal.valueOf(p, 2)).toList();
        List<java.math.BigDecimal> buckets = List.of(
                java.math.BigDecimal.valueOf(ageing.stream().mapToLong(AgeingRow::days0to30).sum(), 2),
                java.math.BigDecimal.valueOf(ageing.stream().mapToLong(AgeingRow::days31to60).sum(), 2),
                java.math.BigDecimal.valueOf(ageing.stream().mapToLong(AgeingRow::days61to90).sum(), 2),
                java.math.BigDecimal.valueOf(ageing.stream().mapToLong(AgeingRow::over90).sum(), 2));
        return new Dashboard(period, total, taxable, outstanding, top,
                orders.ordersWithStatus(OrderStatus.SUBMITTED).size(), flagged, finished, costText, marginText,
                days, amounts, buckets);
    }
}
