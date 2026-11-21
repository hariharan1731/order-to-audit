package com.orderaudit.report;

public enum ReportFormat {
    CSV("csv", "text/csv; charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String contentType;

    ReportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() { return extension; }
    public String contentType() { return contentType; }
}
