package com.orderaudit.web;

import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import com.orderaudit.service.DuplicateItemException;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.StockQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The item master: every raw material, packaging item and finished product, and a form to add one. */
@Controller
@RequestMapping("/admin/items")
public class ItemAdminController {

    private final ItemService itemService;
    private final StockQueryService stockQuery;

    public ItemAdminController(ItemService itemService, StockQueryService stockQuery) {
        this.itemService = itemService;
        this.stockQuery = stockQuery;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", itemService.all());
        model.addAttribute("types", ItemType.values());
        model.addAttribute("units", List.of(Unit.GRAM, Unit.MILLILITRE, Unit.PIECE, Unit.PACKET));
        return "admin/items";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam ItemType type, @RequestParam Unit baseUnit,
                         @RequestParam(required = false) String hsnCode, @RequestParam String gstRate,
                         RedirectAttributes redirect) {
        try {
            itemService.create(name, type, baseUnit, hsnCode, new BigDecimal(gstRate.trim()));
            redirect.addFlashAttribute("message", "Item added.");
        } catch (DuplicateItemException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/items";
    }

    @PostMapping("/{id}")
    public String update(@org.springframework.web.bind.annotation.PathVariable long id,
                         @RequestParam(required = false) String hsnCode, @RequestParam String gstRate,
                         RedirectAttributes redirect) {
        try {
            itemService.updateTaxDetails(id, hsnCode, new BigDecimal(gstRate.trim()));
            redirect.addFlashAttribute("message", "Item updated. Invoices already raised keep their old rate.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/items";
    }

    @PostMapping("/{id}/active")
    public String setActive(@org.springframework.web.bind.annotation.PathVariable long id,
                            @RequestParam boolean active, RedirectAttributes redirect) {
        try {
            itemService.setActive(id, active);
            redirect.addFlashAttribute("message", active ? "Item switched on." : "Item switched off.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/items";
    }
}
