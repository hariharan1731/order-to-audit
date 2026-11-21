package com.orderaudit.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared input rules for customers and suppliers. Package-private: only the services here use it. */
final class PartyValidation {

    private static final Pattern GSTIN =
            Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    private PartyValidation() {
    }

    static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 120) {
            throw new IllegalArgumentException("Name is required and must be at most 120 characters");
        }
        return clean;
    }

    static String cleanStateCode(String stateCode) {
        String clean = stateCode == null ? "" : stateCode.trim();
        if (!clean.matches("\\d{2}")) {
            throw new IllegalArgumentException("State code must be exactly 2 digits (e.g. 33 for Tamil Nadu)");
        }
        return clean;
    }

    /** Returns null for an empty GSTIN (unregistered party). Otherwise checks format and state prefix. */
    static String cleanGstin(String gstin, String stateCode) {
        if (gstin == null || gstin.isBlank()) {
            return null;
        }
        String clean = gstin.trim().toUpperCase(Locale.ROOT);
        if (!GSTIN.matcher(clean).matches()) {
            throw new IllegalArgumentException("GSTIN must be 15 characters in the standard format");
        }
        if (!clean.startsWith(stateCode)) {
            throw new IllegalArgumentException("GSTIN starts with " + clean.substring(0, 2)
                    + " but the state code is " + stateCode);
        }
        return clean;
    }

    static String cleanAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String clean = address.trim();
        if (clean.length() > 300) {
            throw new IllegalArgumentException("Address must be at most 300 characters");
        }
        return clean;
    }
}
