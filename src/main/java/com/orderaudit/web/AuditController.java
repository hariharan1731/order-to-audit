package com.orderaudit.web;

import com.orderaudit.service.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Read-only view of the audit trail (newest 300 entries). Nobody can edit it, not even through the database. */
@Controller
@RequestMapping("/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("entries", audit.recent(300));
        return "admin/audit";
    }
}
