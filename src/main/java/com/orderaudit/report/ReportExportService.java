package com.orderaudit.report;

import java.util.Locale;
import org.springframework.stereotype.Service;

/** One entry point: give it a finished report and a format, get a file. */
@Service
public class ReportExportService {

    public ExportedFile export(ReportTable table, ReportFormat format) {
        byte[] content = switch (format) {
            case CSV -> CsvRenderer.render(table);
            case XLSX -> XlsxRenderer.render(table);
            case PDF -> PdfRenderer.render(table);
        };
        return new ExportedFile(slug(table.title()) + "." + format.extension(), format.contentType(), content);
    }

    static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "report" : slug;
    }
}
