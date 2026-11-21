package com.orderaudit.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.Unit;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportModelTest {

    @Test
    void aCellOfTheWrongTypeIsRejected() {
        assertThatThrownBy(() -> new ReportTable("T", null, List.of(ReportColumn.money("M")),
                List.of(List.<Object>of("text")), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot hold");
    }

    @Test
    void aRowOfTheWrongWidthIsRejected() {
        assertThatThrownBy(() -> new ReportTable("T", null, List.of(ReportColumn.money("M")),
                List.of(List.<Object>of(1L, 2L)), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cells");
    }

    @Test
    void aReportNeedsATitle() {
        assertThatThrownBy(() -> new ReportTable(" ", null, List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayTextUsesThousandsSeparatorsButPlainTextDoesNot() {
        assertThat(CellText.display(ColumnType.MONEY, 787_500L)).isEqualTo("7,875.00");
        assertThat(CellText.plain(ColumnType.MONEY, 787_500L)).isEqualTo("7875.00");
        assertThat(CellText.display(ColumnType.QUANTITY, 1_234_567L)).isEqualTo("1,234,567");
        assertThat(CellText.display(ColumnType.MONEY, null)).isEmpty();
    }

    @Test
    void quantitiesAreShownInReadableUnits() {
        assertThat(QuantityFormat.display(120_000, Unit.GRAM)).isEqualTo("120 kg");
        assertThat(QuantityFormat.display(3_800, Unit.GRAM)).isEqualTo("3.8 kg");
        assertThat(QuantityFormat.display(1, Unit.GRAM)).isEqualTo("0.001 kg");
        assertThat(QuantityFormat.display(0, Unit.GRAM)).isEqualTo("0 kg");
        assertThat(QuantityFormat.display(19_500, Unit.MILLILITRE)).isEqualTo("19.5 L");
        assertThat(QuantityFormat.display(306, Unit.PIECE)).isEqualTo("306 pcs");
        assertThat(QuantityFormat.display(88, Unit.PACKET)).isEqualTo("88 packets");
    }

    @Test
    void aPeriodMustNotEndBeforeItStarts() {
        assertThatThrownBy(() -> new ReportPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReportPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).label())
                .isEqualTo("2026-11-01 to 2026-11-30");
    }

    @Test
    void exportFileNamesAreSafeSlugs() {
        assertThat(ReportExportService.slug("Stock ledger - Murukku 200 g")).isEqualTo("stock-ledger-murukku-200-g");
        assertThat(ReportExportService.slug("###")).isEqualTo("report");
    }
}
