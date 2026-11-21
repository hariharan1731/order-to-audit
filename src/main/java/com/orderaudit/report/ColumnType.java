package com.orderaudit.report;

/**
 * What a column holds, which decides the Java type of its cells and how each format shows it.
 *   TEXT      String
 *   DATE      LocalDate
 *   MONEY     Long, whole PAISE (shown as rupees with 2 decimals)
 *   QUANTITY  Long, whole base units or counts
 *   PERCENT   BigDecimal, already a percentage with 2 decimals (-6.00)
 *   DECIMAL4  BigDecimal shown with 4 decimals (rates such as 4.3125 paise per gram)
 */
public enum ColumnType {
    TEXT, DATE, MONEY, QUANTITY, PERCENT, DECIMAL4
}
