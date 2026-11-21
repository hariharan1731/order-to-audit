package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderaudit.domain.AllocationRequest;
import com.orderaudit.domain.OpenInvoice;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentAllocatorTest {

    private static OpenInvoice open(long id, String date, long outstanding) {
        return new OpenInvoice(id, "INV-" + id, LocalDate.parse(date), outstanding, 0, outstanding);
    }

    @Test
    void aSmallPaymentGoesToTheOldestInvoiceOnly() {
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(40_000,
                List.of(open(2, "2026-11-10", 100_000), open(1, "2026-10-01", 100_000)));

        assertThat(plan).containsExactly(new AllocationRequest(1, 40_000));
    }

    @Test
    void aBiggerPaymentClearsTheOldestThenPartPaysTheNext() {
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(130_000,
                List.of(open(2, "2026-11-10", 100_000), open(1, "2026-10-01", 50_000)));

        assertThat(plan).containsExactly(new AllocationRequest(1, 50_000), new AllocationRequest(2, 80_000));
    }

    @Test
    void sameDateInvoicesAreServedInIdOrder() {
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(150,
                List.of(open(7, "2026-11-01", 100), open(5, "2026-11-01", 100)));

        assertThat(plan).containsExactly(new AllocationRequest(5, 100), new AllocationRequest(7, 50));
    }

    @Test
    void fullyPaidInvoicesAreSkipped() {
        List<AllocationRequest> plan = PaymentAllocator.oldestFirst(10,
                List.of(open(1, "2026-10-01", 0), open(2, "2026-10-02", 100)));

        assertThat(plan).containsExactly(new AllocationRequest(2, 10));
    }

    @Test
    void payingMoreThanIsOwedIsRejected() {
        assertThatThrownBy(() -> PaymentAllocator.oldestFirst(101, List.of(open(1, "2026-10-01", 100))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than the total outstanding");
    }

    @Test
    void theAmountMustBePositive() {
        assertThatThrownBy(() -> PaymentAllocator.oldestFirst(0, List.of(open(1, "2026-10-01", 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
