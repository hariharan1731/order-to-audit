package com.orderaudit.web;

import com.orderaudit.domain.Role;
import com.orderaudit.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Login accounts: add staff, switch an account off, reset a password. Passwords are never shown anywhere. */
@Controller
@RequestMapping("/users")
public class UserAdminController {

    private final UserService users;

    public UserAdminController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.all());
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @PostMapping
    public String create(@RequestParam String username, @RequestParam String displayName,
                         @RequestParam String password, @RequestParam Role role, Authentication auth,
                         RedirectAttributes redirect) {
        try {
            users.create(username, displayName, password, role, auth.getName());
            redirect.addFlashAttribute("message", "User " + username.trim() + " created.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/enabled")
    public String setEnabled(@PathVariable long id, @RequestParam boolean enabled, Authentication auth,
                             RedirectAttributes redirect) {
        try {
            users.setEnabled(id, enabled, auth.getName());
            redirect.addFlashAttribute("message", enabled ? "Account enabled." : "Account disabled.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/password")
    public String resetPassword(@PathVariable long id, @RequestParam String password, Authentication auth,
                                RedirectAttributes redirect) {
        try {
            users.changePassword(id, password, auth.getName());
            redirect.addFlashAttribute("message", "Password changed.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/users";
    }
}
