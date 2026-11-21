package com.orderaudit.web;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Money;
import com.orderaudit.service.CustomerService;
import com.orderaudit.service.DuplicatePartyException;
import com.orderaudit.service.ItemService;
import com.orderaudit.service.PriceService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Master data the office maintains: customers and the selling price of each finished product. */
@Controller
@RequestMapping("/admin")
public class AdminMasterController {

    /** A finished product with its current default price (null when none is set yet). */
    public record PriceRow(long itemId, String name, Long pricePaise) {
    }

    private final CustomerService customers;
    private final ItemService items;
    private final PriceService prices;
    private final com.orderaudit.repository.CustomerPriceRepository customerPrices;

    /** One customer-specific price, with names instead of ids. */
    public record SpecialRow(String customer, String item, long pricePaise) {
    }

    public AdminMasterController(CustomerService customers, ItemService items, PriceService prices,
                                 com.orderaudit.repository.CustomerPriceRepository customerPrices) {
        this.customers = customers;
        this.items = items;
        this.prices = prices;
        this.customerPrices = customerPrices;
    }

    @GetMapping("/customers")
    public String customerList(Model model) {
        model.addAttribute("customers", customers.activeList());
        return "admin/customers";
    }

    @PostMapping("/customers")
    public String createCustomer(@RequestParam String name, @RequestParam(required = false) String gstin,
                                 @RequestParam String stateCode, @RequestParam(required = false) String address,
                                 RedirectAttributes redirect) {
        try {
            customers.create(name, gstin, stateCode, address);
            redirect.addFlashAttribute("message", "Customer added.");
        } catch (DuplicatePartyException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/customers";
    }

    @GetMapping("/prices")
    public String priceList(Model model) {
        List<PriceRow> rows = new ArrayList<>();
        for (Item item : items.activeItemsOfType(ItemType.FINISHED)) {
            rows.add(new PriceRow(item.getId(), item.getName(), prices.listPriceOf(item.getId()).orElse(null)));
        }
        model.addAttribute("rows", rows);
        var customerList = customers.activeList();
        model.addAttribute("customers", customerList);
        model.addAttribute("products", items.activeItemsOfType(ItemType.FINISHED));
        List<SpecialRow> specials = new ArrayList<>();
        for (var cp : customerPrices.findAll()) {
            String customer = customerList.stream().filter(c -> c.getId().equals(cp.getCustomerId()))
                    .map(c -> c.getName()).findFirst().orElse("?");
            String item = items.activeItemsOfType(ItemType.FINISHED).stream()
                    .filter(i -> i.getId().equals(cp.getItemId())).map(Item::getName).findFirst().orElse("?");
            specials.add(new SpecialRow(customer, item, cp.getPricePaise()));
        }
        model.addAttribute("specials", specials);
        return "admin/prices";
    }

    @PostMapping("/prices/{itemId}")
    public String setPrice(@PathVariable long itemId, @RequestParam BigDecimal priceRupees, Authentication auth,
                           RedirectAttributes redirect) {
        try {
            prices.setListPrice(itemId, Money.ofRupees(priceRupees).paise());
            redirect.addFlashAttribute("message", "Price saved.");
        } catch (IllegalArgumentException | ArithmeticException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/prices";
    }

    @PostMapping("/prices/special")
    public String setSpecialPrice(@RequestParam long customerId, @RequestParam long itemId,
                                  @RequestParam BigDecimal priceRupees, RedirectAttributes redirect) {
        try {
            prices.setCustomerPrice(customerId, itemId, Money.ofRupees(priceRupees).paise());
            redirect.addFlashAttribute("message", "Special price saved.");
        } catch (IllegalArgumentException | ArithmeticException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/prices";
    }
}
