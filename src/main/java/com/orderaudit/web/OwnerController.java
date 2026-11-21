package com.orderaudit.web;

import com.orderaudit.report.ReportPeriod;
import com.orderaudit.service.DashboardService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The owner's dashboard: this month's sales, who owes money, what needs attention. */
@Controller
@RequestMapping("/owner")
public class OwnerController {

    private final DashboardService dashboard;

    public OwnerController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public String show(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        LocalDate today = ReportPeriod.today();
        model.addAttribute("d", dashboard.compute(new ReportPeriod(from != null ? from : today.withDayOfMonth(1),
                to != null ? to : today)));
        return "owner/dashboard";
    }
}
