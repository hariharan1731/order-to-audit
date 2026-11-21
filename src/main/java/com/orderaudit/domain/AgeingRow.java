package com.orderaudit.domain;

/** One customer's unpaid amount split into age buckets (paise). */
public record AgeingRow(long customerId, String customerName, long days0to30, long days31to60,
                        long days61to90, long over90, long total) {
}
