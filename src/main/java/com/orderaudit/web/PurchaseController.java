package com.orderaudit.web;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.service.DuplicatePartyException;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.PurchaseService;
import com.orderaudit.service.SupplierService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Goods received from a supplier: quantities in kg / L / pieces, the rate per that same unit. */
@Controller
@RequestMapping("/stock/purchases")
public class PurchaseController {

    /** An item a purchase can be made of, with the unit label shown next to its boxes. */
    public record Buyable(long id, String name, String unit) {
    }

    private final PurchaseService purchases;
    private final SupplierService suppliers;
    private final ItemService itemService;
    private final ItemRepository items;

    public PurchaseController(PurchaseService purchases, SupplierService suppliers, ItemService itemService,
                              ItemRepository items) {
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.itemService = itemService;
        this.items = items;
    }

    @GetMapping("/new")
    public String form(Model model) {
        List<Buyable> buyable = new ArrayList<>();
        for (ItemType type : new ItemType[] {ItemType.RAW, ItemType.PACKAGING}) {
            for (Item i : itemService.activeItemsOfType(type)) {
                buyable.add(new Buyable(i.getId(), i.getName(), UnitInput.label(i.getBaseUnit())));
            }
        }
        model.addAttribute("suppliers", suppliers.activeList());
        model.addAttribute("buyable", buyable);
        model.addAttribute("today", ReportPeriod.today());
        model.addAttribute("rows", new int[] {0, 1, 2});
        return "stock/purchase";
    }

    @PostMapping
    public String record(@RequestParam long supplierId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate purchaseDate,
                         @RequestParam(required = false) String reference,
                         @RequestParam(name = "itemId", required = false) List<String> itemIds,
                         @RequestParam(name = "quantity", required = false) List<String> quantities,
                         @RequestParam(name = "rate", required = false) List<String> rates,
                         Authentication auth, RedirectAttributes redirect) {
        try {
            List<PurchaseLineSpec> specs = new ArrayList<>();
            for (int i = 0; itemIds != null && i < itemIds.size(); i++) {
                String itemText = itemIds.get(i);
                String qty = quantities != null && i < quantities.size() ? quantities.get(i) : "";
                if (itemText == null || itemText.isBlank() || qty == null || qty.isBlank()) {
                    continue;                                    // an unused row
                }
                Item item = items.findById(Long.parseLong(itemText)).orElseThrow(
                        () -> new IllegalArgumentException("Unknown item"));
                String rate = rates != null && i < rates.size() ? rates.get(i) : "";
                if (rate == null || rate.isBlank()) {
                    throw new IllegalArgumentException("Enter the rate for " + item.getName());
                }
                specs.add(new PurchaseLineSpec(item.getId(), UnitInput.toBase(qty, item.getBaseUnit()),
                        UnitInput.ratePerBaseUnit(rate, item.getBaseUnit())));
            }
            purchases.record(supplierId, purchaseDate, reference, specs, auth.getName());
            redirect.addFlashAttribute("message", "Purchase recorded. The stock has gone up.");
            return "redirect:/stock";
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/stock/purchases/new";
        }
    }

    @PostMapping("/suppliers")
    public String addSupplier(@RequestParam String name, @RequestParam(required = false) String gstin,
                              @RequestParam String stateCode, @RequestParam(required = false) String address,
                              RedirectAttributes redirect) {
        try {
            suppliers.create(name, gstin, stateCode, address);
            redirect.addFlashAttribute("message", "Supplier added.");
        } catch (DuplicatePartyException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/stock/purchases/new";
    }
}
