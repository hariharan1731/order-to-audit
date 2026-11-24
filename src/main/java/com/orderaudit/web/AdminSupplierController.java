package com.orderaudit.web;

import com.orderaudit.service.DuplicatePartyException;
import com.orderaudit.service.SupplierService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The office's supplier list: add, correct the details, switch a supplier off or on. Thin: rules live in the service. */
@Controller
@RequestMapping("/admin/suppliers")
public class AdminSupplierController {

    private final SupplierService suppliers;

    public AdminSupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("suppliers", suppliers.all());
        return "admin/suppliers";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam(required = false) String gstin,
                         @RequestParam String stateCode, @RequestParam(required = false) String address,
                         RedirectAttributes redirect) {
        try {
            suppliers.create(name, gstin, stateCode, address);
            redirect.addFlashAttribute("message", "Supplier added.");
        } catch (DuplicatePartyException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/suppliers";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @RequestParam String name,
                         @RequestParam(required = false) String gstin, @RequestParam String stateCode,
                         @RequestParam(required = false) String address, RedirectAttributes redirect) {
        try {
            suppliers.update(id, name, gstin, stateCode, address);
            redirect.addFlashAttribute("message", "Supplier saved.");
        } catch (DuplicatePartyException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/suppliers";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable long id, @RequestParam boolean active, RedirectAttributes redirect) {
        try {
            suppliers.setActive(id, active);
            redirect.addFlashAttribute("message", active ? "Supplier switched on." : "Supplier switched off. Old purchases are untouched.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/suppliers";
    }
}
