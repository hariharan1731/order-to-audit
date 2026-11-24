package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockAdjustment;
import com.orderaudit.repository.StockAdjustmentRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    @Mock StockAdjustmentRepository adjustments;
    @Mock StockService stock;
    @Mock AuditService audit;
    @InjectMocks StockAdjustmentService service;

    /** The fake repository gives the saved header the id 9, as the database would. */
    private void headerGetsId9() {
        when(adjustments.save(any(StockAdjustment.class))).thenAnswer(call -> {
            StockAdjustment header = call.getArgument(0);
            ReflectionTestUtils.setField(header, "id", 9L);
            return header;
        });
    }

    @Test
    void openingStockPostsAnOpeningMovementLinkedToTheHeader() {
        headerGetsId9();

        service.recordOpening(1, 100_000, new BigDecimal("4.2"), "Opening stock", "admin");

        verify(stock).post(1L, MovementType.OPENING, 100_000L, new BigDecimal("4.2"),
                SourceType.STOCK_ADJUSTMENT, 9L, "admin");
    }

    @Test
    void openingStockMayBeUnvalued() {
        headerGetsId9();

        service.recordOpening(2, 50, null, "Opening stock", "admin");

        verify(stock).post(eq(2L), eq(MovementType.OPENING), eq(50L), isNull(),
                eq(SourceType.STOCK_ADJUSTMENT), eq(9L), eq("admin"));
    }

    @Test
    void openingStockMustBePositive() {
        assertThatThrownBy(() -> service.recordOpening(1, -5, null, "x", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(adjustments, stock);
    }

    @Test
    void aCorrectionCanBeNegativeAndPostsAnAdjustMovement() {
        headerGetsId9();

        service.recordCorrection(1, -5, null, "Spillage found at count", "admin");

        verify(stock).post(eq(1L), eq(MovementType.ADJUST), eq(-5L), isNull(),
                eq(SourceType.STOCK_ADJUSTMENT), eq(9L), eq("admin"));
    }

    @Test
    void aReasonIsMandatoryAndZeroIsNeverAllowed() {
        assertThatThrownBy(() -> service.recordCorrection(1, -5, null, "  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordCorrection(1, 0, null, "reason", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(adjustments, stock);
    }
}
