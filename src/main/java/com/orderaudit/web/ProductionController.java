package com.orderaudit.web;

import com.orderaudit.domain.ActualLine;
import com.orderaudit.domain.Item;
import com.orderaudit.domain.Money;
import com.orderaudit.domain.OverheadInput;
import com.orderaudit.domain.ProductionRun;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.service.CostUnknownException;
import com.orderaudit.service.InsufficientStockException;
import com.orderaudit.service.ProductionQueryService;
import com.orderaudit.service.ProductionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The production supervisor's screens: pick the product, see what the recipe plans, type what was really used,
 * the good packets and the overheads. Quantities are typed in kg / L and converted exactly to grams / ml.
 */
@Controller
@RequestMapping("/production")
public class ProductionController {

    private final ProductionService production;
    private final ProductionQueryService queries;
    private final ItemRepository items;

    public ProductionController(ProductionService production, ProductionQueryService queries, ItemRepository items) {
        this.production = production;
        this.queries = queries;
        this.items = items;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("runs", queries.recent());
        return "production/index";
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) Long packConfigId,
                       @RequestParam(defaultValue = "1") BigDecimal batches, Model model, RedirectAttributes redirect) {
        model.addAttribute("options", queries.packOptions());
        model.addAttribute("today", ReportPeriod.today());
        model.addAttribute("batches", batches);
        if (packConfigId != null) {
            try {
                model.addAttribute("plan", queries.plan(packConfigId, batches));
                model.addAttribute("selected", packConfigId);
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "production/new";
    }

    @PostMapping("/runs")
    public String complete(@RequestParam long packConfigId, @RequestParam BigDecimal batches,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate runDate,
                           @RequestParam long goodPackets, @RequestParam long damagedPouches,
                           @RequestParam(name = "itemId", required = false) List<Long> itemIds,
                           @RequestParam(name = "actual", required = false) List<String> actuals,
                           @RequestParam(required = false) String labour, @RequestParam(required = false) String gasPower,
                           @RequestParam(required = false) String otherDescription,
                           @RequestParam(required = false) String otherAmount,
                           Authentication auth, RedirectAttributes redirect) {
        try {
            List<ActualLine> actualLines = new ArrayList<>();
            for (int i = 0; itemIds != null && actuals != null && i < itemIds.size() && i < actuals.size(); i++) {
                String typed = actuals.get(i);
                if (typed == null || typed.isBlank()) {
                    continue;                                   // blank = used exactly as planned
                }
                Item item = items.findById(itemIds.get(i)).orElseThrow(
                        () -> new IllegalArgumentException("Unknown item"));
                actualLines.add(new ActualLine(item.getId(), UnitInput.toBase(typed, item.getBaseUnit())));
            }
            List<OverheadInput> overheads = new ArrayList<>();
            addOverhead(overheads, "Labour", labour);
            addOverhead(overheads, "Gas and power", gasPower);
            addOverhead(overheads, otherDescription == null || otherDescription.isBlank() ? "Other" : otherDescription.trim(),
                    otherAmount);
            ProductionRun run = production.complete(packConfigId, batches, runDate, goodPackets, damagedPouches,
                    actualLines, overheads, auth.getName());
            redirect.addFlashAttribute("message", "Production run #" + run.getId() + " posted.");
            return "redirect:/production/runs/" + run.getId();
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException | InsufficientStockException
                 | CostUnknownException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/production/new?packConfigId=" + packConfigId + "&batches=" + batches.toPlainString();
        }
    }

    private static void addOverhead(List<OverheadInput> list, String description, String rupees) {
        if (rupees == null || rupees.isBlank()) {
            return;
        }
        long paise;
        try {
            paise = Money.ofRupees(new BigDecimal(rupees.trim())).paise();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + rupees + "' is not a valid amount");
        }
        if (paise > 0) {
            list.add(new OverheadInput(description, paise));
        }
    }

    @GetMapping("/runs/{id}")
    public String detail(@PathVariable long id, Model model) {
        try {
            model.addAttribute("run", queries.detail(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return "production/run";
    }

    @PostMapping("/runs/{id}/reverse")
    public String reverse(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        try {
            production.reverse(id, auth.getName());
            redirect.addFlashAttribute("message", "Run #" + id + " reversed. Stock is back to what it was before the run.");
        } catch (IllegalArgumentException | IllegalStateException | InsufficientStockException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/production/runs/" + id;
    }
}
