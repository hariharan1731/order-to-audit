package com.orderaudit.security;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may open which part of the website. The rules are checked top to bottom and the FIRST match wins,
 * so the specific paths come first and "everything else must be logged in" comes last.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Bcrypt with a random salt per password; the stored value starts with {bcrypt}. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Makes Spring Security announce sign-ins and failures as events, so LoginAuditListener can log them. */
    @Bean
    org.springframework.security.authentication.AuthenticationEventPublisher authenticationEventPublisher(
            org.springframework.context.ApplicationEventPublisher publisher) {
        return new org.springframework.security.authentication.DefaultAuthenticationEventPublisher(publisher);
    }

    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .requestMatchers("/sales/**").hasAnyRole("SALESMAN", "ADMIN", "OWNER")
                .requestMatchers("/production/**").hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN", "OWNER")
                // The accountant may LOOK at stock but never change it: only GET is open to that role.
                .requestMatchers(HttpMethod.GET, "/stock/**").hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN", "ACCOUNTANT", "OWNER")
                .requestMatchers("/stock/**").hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN", "OWNER")
                .requestMatchers("/reports/**").hasAnyRole("ACCOUNTANT", "ADMIN", "OWNER")
                .requestMatchers("/owner/**").hasRole("OWNER")
                .requestMatchers("/admin/**", "/users/**", "/audit/**").hasAnyRole("ADMIN", "OWNER")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", false)
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll());
        // CSRF protection stays ON (the default): every form must carry the hidden token Thymeleaf adds.
        return http.build();
    }
}
