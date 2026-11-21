package com.orderaudit.web;

import com.orderaudit.report.ExportedFile;
import com.orderaudit.report.ReportExportService;
import com.orderaudit.report.ReportFormat;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.report.ReportService;
import com.orderaudit.report.ReportTable;
import com.orderaudit.service.StockQueryService;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/** One page to pick a report, a period and a format. The same table is shown on screen or downloaded. */
@Controller
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reports;
    private final ReportExportService exporter;
    private final StockQueryService stockQuery;

    public ReportController(ReportService reports, ReportExportService exporter, StockQueryService stockQuery) {
        this.reports = reports;
        this.exporter = exporter;
        this.stockQuery = stockQuery;
    }

    @GetMapping
    public String index(Model model) {
        LocalDate today = ReportPeriod.today();
        model.addAttribute("keys", ReportKey.values());
        model.addAttribute("from", today.withDayOfMonth(1));
        model.addAttribute("to", today);
        model.addAttribute("items", stockQuery.allItems());
        return "reports/index";
    }

    /** The form on the index page sends everything here; we send the browser on to the right address. */
    @GetMapping("/run")
    public String run(@RequestParam String slug, @RequestParam(defaultValue = "html") String format,
                      @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                      @RequestParam(required = false) String asOf, @RequestParam(required = false) String itemId) {
        String path = format.equals("html") ? "/reports/" + slug : "/reports/" + slug + "/download";
        UriComponentsBuilder target = UriComponentsBuilder.fromPath(path);
        if (!format.equals("html")) {
            target.queryParam("format", format);
        }
        if (from != null && !from.isBlank()) { target.queryParam("from", from); }
        if (to != null && !to.isBlank()) { target.queryParam("to", to); }
        if (asOf != null && !asOf.isBlank()) { target.queryParam("asOf", asOf); }
        if (itemId != null && !itemId.isBlank()) { target.queryParam("itemId", itemId); }
        return "redirect:" + target.build().toUriString();
    }

    @GetMapping("/{slug}")
    public String view(@PathVariable String slug,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                       @RequestParam(required = false) Long itemId, Model model, RedirectAttributes redirect) {
        try {
            ReportTable table = build(slug, from, to, asOf, itemId);
            model.addAttribute("table", HtmlTable.of(table));
            model.addAttribute("slug", slug);
            String query = queryString(from, to, asOf, itemId);
            model.addAttribute("downloadBase", "/reports/" + slug + "/download?" + (query.isEmpty() ? "" : query + "&") + "format=");
            return "reports/view";
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/reports";
        }
    }

    @GetMapping("/{slug}/download")
    public ResponseEntity<byte[]> download(@PathVariable String slug, @RequestParam String format,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                       @RequestParam(required = false) Long itemId) {
        ReportFormat fileFormat;
        try {
            fileFormat = ReportFormat.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown format " + format);
        }
        ReportTable table;
        try {
            table = build(slug, from, to, asOf, itemId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ExportedFile file = exporter.export(table, fileFormat);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.fileName()).build());
        return new ResponseEntity<>(file.content(), headers, HttpStatus.OK);
    }

    private ReportTable build(String slug, LocalDate from, LocalDate to, LocalDate asOf, Long itemId) {
        ReportKey key = ReportKey.fromSlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown report"));
        LocalDate today = ReportPeriod.today();
        ReportPeriod period = new ReportPeriod(from != null ? from : today.withDayOfMonth(1), to != null ? to : today);
        return switch (key) {
            case SALES_REGISTER -> reports.salesRegister(period);
            case CUSTOMER_SALES -> reports.customerWiseSales(period);
            case PRODUCT_SALES -> reports.productWiseSales(period);
            case GST_SUMMARY -> reports.gstSummary(period);
            case PURCHASE_REGISTER -> reports.purchaseRegister(period);
            case STOCK_SUMMARY -> reports.stockSummary(period);
            case STOCK_LEDGER -> {
                if (itemId == null) {
                    throw new IllegalArgumentException("Choose an item for the stock ledger");
                }
                yield reports.stockLedger(itemId, period);
            }
            case PRODUCTION_YIELD -> reports.productionYield(period);
            case PRODUCTION_CONSUMPTION -> reports.productionConsumption(period);
            case COST_PER_PACKET -> reports.costPerPacket(period);
            case COLLECTIONS -> reports.collections(period);
            case OUTSTANDING -> reports.outstandingAndAgeing(asOf != null ? asOf : today);
        };
    }

    private static String queryString(LocalDate from, LocalDate to, LocalDate asOf, Long itemId) {
        UriComponentsBuilder b = UriComponentsBuilder.newInstance();
        if (from != null) { b.queryParam("from", from); }
        if (to != null) { b.queryParam("to", to); }
        if (asOf != null) { b.queryParam("asOf", asOf); }
        if (itemId != null) { b.queryParam("itemId", itemId); }
        String q = b.build().toUriString();
        return q.startsWith("?") ? q.substring(1) : q;
    }
}
