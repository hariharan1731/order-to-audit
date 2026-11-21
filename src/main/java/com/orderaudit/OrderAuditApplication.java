package com.orderaudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point. @SpringBootApplication turns on component scanning of com.orderaudit.* */
@SpringBootApplication
public class OrderAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderAuditApplication.class, args);
    }
}
