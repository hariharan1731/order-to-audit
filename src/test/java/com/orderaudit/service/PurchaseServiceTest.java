package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.MovementType;
import com.orderaudit.domain.Purchase;
import com.orderaudit.domain.PurchaseLine;
import com.orderaudit.domain.PurchaseLineSpec;
import com.orderaudit.domain.SourceType;
import com.orderaudit.domain.Supplier;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PurchaseLineRepository;
import com.orderaudit.repository.PurchaseRepository;
import com.orderaudit.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Mock PurchaseRepository purchases;
    @Mock PurchaseLineRepository lines;
    @Mock SupplierRepository suppliers;
    @Mock ItemRepository items;
    @Mock StockService stock;
    @InjectMocks PurchaseService service;

    private final Supplier supplier = new Supplier("Flour Mill", null, "33", null);

    @BeforeEach
    void commonStubs() {
        Purchase saved = mock(Purchase.class);
        lenient().when(saved.getId()).thenReturn(7L);
        lenient().when(purchases.save(any(Purchase.class))).thenReturn(saved);
        lenient().when(suppliers.findById(1L)).thenReturn(Optional.of(supplier));
        lenient().when(items.findById(1L)).thenReturn(Optional.of(
                new Item("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO)));
        lenient().when(items.findById(2L)).thenReturn(Optional.of(
                new Item("Oil", ItemType.RAW, Unit.MILLILITRE, null, BigDecimal.ZERO)));
        lenient().when(items.findById(3L)).thenReturn(Optional.of(
                new Item("Murukku", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"))));
    }

    private static PurchaseLineSpec line(long itemId, long qty, String cost) {
        return new PurchaseLineSpec(itemId, qty, new BigDecimal(cost));
    }

    @Test
    void officialPurchaseOf60KgFlourAt45RupeesPostsOnePurchaseMovement() {
        service.record(1L, DAY, "PO-1", List.of(line(1, 60_000, "4.5")), "admin");

        verify(stock).post(1L, MovementType.PURCHASE, 60_000, new BigDecimal("4.5"),
                SourceType.PURCHASE, 7L, "admin");
        verify(lines).save(any(PurchaseLine.class));
    }

    @Test
    void postsLinesInAscendingItemIdOrderWhateverOrderTheyWereEntered() {
        service.record(1L, DAY, null, List.of(line(2, 1_000, "14"), line(1, 5_000, "4.5")), "admin");

        InOrder order = inOrder(stock);
        order.verify(stock).post(eq(1L), eq(MovementType.PURCHASE), anyLong(), any(), eq(SourceType.PURCHASE),
                eq(7L), eq("admin"));
        order.verify(stock).post(eq(2L), eq(MovementType.PURCHASE), anyLong(), any(), eq(SourceType.PURCHASE),
                eq(7L), eq("admin"));
    }

    @Test
    void aFinishedProductCannotBePurchased() {
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(line(3, 10, "5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW or PACKAGING");
        verify(stock, never()).post(anyLong(), any(), anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void theSameItemTwiceIsRejected() {
        assertThatThrownBy(() -> service.record(1L, DAY, null,
                List.of(line(1, 10, "4.5"), line(1, 20, "4.5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    @Test
    void quantityMustBePositive() {
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(line(1, 0, "4.5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void costMustNotBeNegativeAndAtMostFourDecimals() {
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(line(1, 10, "-1")), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(line(1, 10, "4.12345")), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPurchaseNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSupplierMustExist() {
        assertThatThrownBy(() -> service.record(99L, DAY, null, List.of(line(1, 10, "4.5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Supplier");
    }

    @Test
    void anInactiveSupplierIsRejected() {
        Supplier inactive = mock(Supplier.class);
        when(inactive.isActive()).thenReturn(false);
        when(inactive.getName()).thenReturn("Old Mill");
        when(suppliers.findById(5L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.record(5L, DAY, null, List.of(line(1, 10, "4.5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not active");
    }

    @Test
    void dateAndUserAreRequired() {
        assertThatThrownBy(() -> service.record(1L, null, null, List.of(line(1, 10, "4.5")), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(1L, DAY, null, List.of(line(1, 10, "4.5")), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsThePurchaseThatWasSaved() {
        Purchase result = service.record(1L, DAY, "PO-1", List.of(line(1, 10, "4.5")), "admin");

        assertThat(result.getId()).isEqualTo(7L);
    }
}
