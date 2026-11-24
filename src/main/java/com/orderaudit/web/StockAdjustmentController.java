package com.orderaudit.web;

import com.orderaudit.domain.Item;
import com.orderaudit.service.InsufficientStockException;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.StockAdjustmentService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Opening stock and manual corrections (damage, wastage, a miscount). The staff type kg / L / pieces; the
 * rules (a reason is mandatory, stock never goes below zero, everything is audited) live in the services.
 */
@Controller
@RequestMapping("/stock/adjustments")
public class StockAdjustmentController {

    /** One item on the form, with the unit label shown next to the quantity box. */
    public record Choice(long id, String name, String unit) {
    }

    private final StockAdjustmentService adjustments;
    private final ItemService itemService;

    public StockAdjustmentController(StockAdjustmentService adjustments, ItemService itemService) {
        this.adjustments = adjustments;
        this.itemService = itemService;
    }

    @GetMapping("/new")
    public String form(Model model) {
        List<Choice> choices = itemService.all().stream()
                .filter(Item::isActive)
                .map(i -> new Choice(i.getId(), i.getName(), UnitInput.label(i.getBaseUnit())))
                .toList();
        model.addAttribute("choices", choices);
        return "stock/adjustment";
    }

    /** action: OPENING (stock in, first balance), ADD (found / recounted up) or REMOVE (damaged, wasted, lost). */
    @PostMapping
    public String record(@RequestParam long itemId, @RequestParam String action, @RequestParam String quantity,
                         @RequestParam(required = false) String rate, @RequestParam String reason,
                         Authentication auth, RedirectAttributes redirect) {
        try {
            Item item = itemService.all().stream().filter(i -> i.getId() == itemId).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown item"));
            long base = UnitInput.toBase(quantity, item.getBaseUnit());
            if (base <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero");
            }
            BigDecimal unitCost = rate == null || rate.isBlank() ? null
                    : UnitInput.ratePerBaseUnit(rate, item.getBaseUnit());
            switch (action) {
                case "OPENING" -> adjustments.recordOpening(itemId, base, unitCost, reason, auth.getName());
                case "ADD" -> adjustments.recordCorrection(itemId, base, unitCost, reason, auth.getName());
                case "REMOVE" -> adjustments.recordCorrection(itemId, -base, null, reason, auth.getName());
                default -> throw new IllegalArgumentException("Choose what you are recording");
            }
            redirect.addFlashAttribute("message", "Stock updated for " + item.getName() + ". It is in the ledger and the audit log.");
            return "redirect:/stock";
        } catch (IllegalArgumentException | IllegalStateException | InsufficientStockException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/stock/adjustments/new";
        }
    }
}
