package com.orderaudit.web;

import com.orderaudit.report.ReportPeriod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Display helpers for the templates, used as ${@fmt.rupees(x)}. Money is held as paise and only formatted here. */
@Component("fmt")
public class Formats {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    /** 787500 -> "Rs 7,875.00" (exact: built from the paise as a decimal, never from a binary fraction). */
    public String rupees(long paise) {
        BigDecimal amount = BigDecimal.valueOf(paise, 2);
        return "Rs " + String.format(Locale.ENGLISH, "%,.2f", amount);
    }

    /** 5000 -> "50.00": for form inputs, no currency label and no thousands separator. */
    public String plainRupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    /** A cost kept with extra decimals (paise, e.g. 3114.3617) shown as rupees rounded to the paisa: "Rs 31.14". */
    public String rupeesRounded(java.math.BigDecimal paise) {
        return paise == null ? "" : rupees(com.orderaudit.service.CostMath.roundToPaise(paise));
    }

    /** The Bootstrap icon that goes with an order or run status, so every screen shows the same picture. */
    public String statusIcon(Object status) {
        String name = status == null ? "" : status.toString();
        return switch (name) {
            case "SUBMITTED" -> "bi-hourglass-split";
            case "APPROVED" -> "bi-check2-circle";
            case "INVOICED" -> "bi-file-earmark-text";
            case "DISPATCHED" -> "bi-truck";
            case "REJECTED" -> "bi-x-circle";
            case "POSTED" -> "bi-check2-circle";
            case "REVERSED" -> "bi-arrow-counterclockwise";
            case "ISSUED" -> "bi-file-earmark-check";
            case "CANCELLED" -> "bi-x-octagon";
            default -> "bi-dot";
        };
    }

    public String number(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    public String dateTime(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant.atZone(ReportPeriod.ZONE));
    }

    public String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }
}
