package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.StockBalance;
import com.orderaudit.domain.StockMovement;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.StockBalanceRepository;
import com.orderaudit.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock StockMovementRepository movements;
    @Mock StockBalanceRepository balances;
    @Mock ItemRepository items;
    @InjectMocks StockService service;

    private final Item flour = new Item("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);

    /** Item 1 exists and has this balance. */
    private StockBalance flourWithBalance(long qty) {
        StockBalance balance = new StockBalance(1L, qty);
        when(items.findById(1L)).thenReturn(Optional.of(flour));
        when(balances.findForUpdate(1L)).thenReturn(Optional.of(balance));
        lenient().when(movements.save(any(StockMovement.class))).thenAnswer(call -> call.getArgument(0));
        return balance;
    }

    /** For reversals the item itself is not looked up. */
    private StockBalance balanceOnly(long qty) {
        StockBalance balance = new StockBalance(1L, qty);
        when(balances.findForUpdate(1L)).thenReturn(Optional.of(balance));
        lenient().when(movements.save(any(StockMovement.class))).thenAnswer(call -> call.getArgument(0));
        return balance;
    }

    private static StockMovement original(MovementType type, long qty) {
        return new StockMovement(1L, type, qty, new BigDecimal("4.5"), SourceType.PURCHASE, 7L, null, "u");
    }

    @Test
    void aReceiptIncreasesTheBalanceAndAppendsOneMovement() {
        StockBalance balance = flourWithBalance(100_000);

        StockMovement m = service.post(1, MovementType.PURCHASE, 60_000, new BigDecimal("4.5"),
                SourceType.PURCHASE, 7, "tester");

        assertThat(balance.getQtyBase()).isEqualTo(160_000);
        assertThat(m.getQtySigned()).isEqualTo(60_000);
        assertThat(m.getMovementType()).isEqualTo(MovementType.PURCHASE);
        assertThat(m.getSourceType()).isEqualTo(SourceType.PURCHASE);
        assertThat(m.getSourceId()).isEqualTo(7L);
        verify(balances).ensureRow(1L);
    }

    @Test
    void anIssueBeyondTheBalanceIsBlockedAndNothingIsSaved() {
        StockBalance balance = flourWithBalance(100);

        assertThatThrownBy(() -> service.post(1, MovementType.PROD_ISSUE, -101, null,
                SourceType.PRODUCTION_RUN, 1, "tester"))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(balance.getQtyBase()).isEqualTo(100);
        verify(movements, never()).save(any());
    }

    @Test
    void anIssueOfExactlyTheBalanceIsAllowed() {
        StockBalance balance = flourWithBalance(100);

        service.post(1, MovementType.PROD_ISSUE, -100, null, SourceType.PRODUCTION_RUN, 1, "tester");

        assertThat(balance.getQtyBase()).isZero();
    }

    @Test
    void theWrongDirectionForATypeIsRejectedBeforeAnythingIsTouched() {
        assertThatThrownBy(() -> service.post(1, MovementType.OPENING, -5, null, SourceType.STOCK_ADJUSTMENT, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.SALE, 5, null, SourceType.DISPATCH, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 0, null, SourceType.PURCHASE, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.PROD_ISSUE, 0, null, SourceType.PRODUCTION_RUN, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(items, balances, movements);
    }

    @Test
    void reversalsCannotBePostedThroughPost() {
        assertThatThrownBy(() -> service.post(1, MovementType.REVERSAL, 5, null, SourceType.PURCHASE, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(items, balances, movements);
    }

    @Test
    void anUnknownItemIsRejected() {
        assertThatThrownBy(() -> service.post(99, MovementType.PURCHASE, 5, null, SourceType.PURCHASE, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(balances, movements);
    }

    @Test
    void badUnitCostsAreRejected() {
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 5, new BigDecimal("-1"),
                SourceType.PURCHASE, 1, "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 5, new BigDecimal("1.23456"),
                SourceType.PURCHASE, 1, "t")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(items, balances, movements);
    }

    @Test
    void aMissingSourceOrUserIsRejected() {
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 5, null, null, 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 5, null, SourceType.PURCHASE, 1, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(1, MovementType.PURCHASE, 5, null, SourceType.PURCHASE, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(items, balances, movements);
    }

    @Test
    void aReversalPostsTheExactOppositeAndLinksToTheOriginal() {
        when(movements.findById(5L)).thenReturn(Optional.of(original(MovementType.PURCHASE, 60_000)));
        StockBalance balance = balanceOnly(160_000);

        StockMovement reversal = service.reverse(5, "tester");

        assertThat(reversal.getMovementType()).isEqualTo(MovementType.REVERSAL);
        assertThat(reversal.getQtySigned()).isEqualTo(-60_000);
        assertThat(reversal.getReversesId()).isEqualTo(5L);
        assertThat(reversal.getSourceType()).isEqualTo(SourceType.PURCHASE);
        assertThat(reversal.getSourceId()).isEqualTo(7L);
        assertThat(balance.getQtyBase()).isEqualTo(100_000);
    }

    @Test
    void aReversalCannotBeReversed() {
        when(movements.findById(5L)).thenReturn(Optional.of(original(MovementType.REVERSAL, -60_000)));

        assertThatThrownBy(() -> service.reverse(5, "tester")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(balances);
    }

    @Test
    void aMovementCannotBeReversedTwice() {
        when(movements.findById(5L)).thenReturn(Optional.of(original(MovementType.PURCHASE, 60_000)));
        when(movements.existsByReversesId(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.reverse(5, "tester")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(balances);
    }

    @Test
    void aReversalThatWouldMakeStockNegativeIsBlocked() {
        when(movements.findById(5L)).thenReturn(Optional.of(original(MovementType.PURCHASE, 60_000)));
        StockBalance balance = balanceOnly(10_000);

        assertThatThrownBy(() -> service.reverse(5, "tester")).isInstanceOf(InsufficientStockException.class);
        assertThat(balance.getQtyBase()).isEqualTo(10_000);
        verify(movements, never()).save(any());
    }

    @Test
    void reversingAnUnknownMovementIsRejected() {
        assertThatThrownBy(() -> service.reverse(404, "tester")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anItemThatHasNeverMovedHasZeroBalance() {
        assertThat(service.balanceOf(1)).isZero();
    }
}
