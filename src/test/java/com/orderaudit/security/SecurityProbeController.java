package com.orderaudit.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** TEST ONLY: one harmless endpoint under each protected area, so the URL rules can be checked. */
@RestController
class SecurityProbeController {

    @GetMapping({"/sales/probe", "/production/probe", "/stock/probe", "/reports/probe", "/owner/probe",
            "/admin/probe", "/users/probe", "/audit/probe", "/other/probe"})
    String probe() {
        return "ok";
    }

    @PostMapping("/sales/probe")
    String post() {
        return "posted";
    }
}
