package com.orderaudit.web;

import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.service.StockQueryService;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/** The store at a glance, and the full history of one item (every movement with a running balance). */
@Controller
@RequestMapping("/stock")
public class StockController {

    private final StockQueryService stockQuery;
    private final ReportService reports;

    public StockController(StockQueryService stockQuery, ReportService reports) {
        this.stockQuery = stockQuery;
        this.reports = reports;
    }

    @GetMapping
    public String overview(Model model) {
        model.addAttribute("rows", stockQuery.snapshot());
        return "stock/overview";
    }

    @GetMapping("/items/{id}")
    public String ledger(@PathVariable long id, Model model) {
        try {
            var table = reports.stockLedger(id, new ReportPeriod(LocalDate.of(2000, 1, 1), ReportPeriod.today().plusDays(1)));
            model.addAttribute("table", HtmlTable.of(table));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return "stock/ledger";
    }
}
