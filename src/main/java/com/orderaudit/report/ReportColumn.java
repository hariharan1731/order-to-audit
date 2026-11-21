package com.orderaudit.report;

public record ReportColumn(String header, ColumnType type) {

    public static ReportColumn text(String header) { return new ReportColumn(header, ColumnType.TEXT); }
    public static ReportColumn date(String header) { return new ReportColumn(header, ColumnType.DATE); }
    public static ReportColumn money(String header) { return new ReportColumn(header, ColumnType.MONEY); }
    public static ReportColumn quantity(String header) { return new ReportColumn(header, ColumnType.QUANTITY); }
    public static ReportColumn percent(String header) { return new ReportColumn(header, ColumnType.PERCENT); }
    public static ReportColumn decimal4(String header) { return new ReportColumn(header, ColumnType.DECIMAL4); }
}
