package com.orderaudit.web;

import com.orderaudit.domain.OrderStatus;
import com.orderaudit.report.ReportPeriod;
import com.orderaudit.service.DashboardService;
import com.orderaudit.service.OrderQueryService;
import java.time.LocalDate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The login page and each role's home page with the few live numbers that role cares about. */
@Controller
public class LoginController {

    private final DashboardService dashboard;
    private final OrderQueryService orderQueries;

    public LoginController(DashboardService dashboard, OrderQueryService orderQueries) {
        this.dashboard = dashboard;
        this.orderQueries = orderQueries;
    }

    /** Spring Security handles the POST; we only draw the page. */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String home(Authentication auth, Model model) {
        boolean salesman = has(auth, "ROLE_SALESMAN");
        boolean office = has(auth, "ROLE_ADMIN") || has(auth, "ROLE_OWNER") || has(auth, "ROLE_ACCOUNTANT")
                || has(auth, "ROLE_PRODUCTION_SUPERVISOR");
        if (office) {
            LocalDate today = ReportPeriod.today();
            model.addAttribute("summary", dashboard.compute(new ReportPeriod(today.withDayOfMonth(1), today)));
        }
        if (salesman) {
            var mine = orderQueries.mine(auth.getName());
            model.addAttribute("myOrders", mine.size());
            model.addAttribute("myWaiting", mine.stream().filter(o -> o.status() == OrderStatus.SUBMITTED).count());
            model.addAttribute("myDispatched", mine.stream().filter(o -> o.status() == OrderStatus.DISPATCHED).count());
        }
        return "home";
    }

    private static boolean has(Authentication auth, String role) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (a.getAuthority().equals(role)) {
                return true;
            }
        }
        return false;
    }
}
