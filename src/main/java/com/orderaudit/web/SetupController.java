package com.orderaudit.web;

import com.orderaudit.service.SetupQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Recipes and pack configurations, read-only: the "how we make and pack it" sheet. */
@Controller
public class SetupController {

    private final SetupQueryService setup;

    public SetupController(SetupQueryService setup) {
        this.setup = setup;
    }

    @GetMapping("/admin/setup")
    public String show(Model model) {
        model.addAttribute("recipes", setup.all());
        return "admin/setup";
    }
}
