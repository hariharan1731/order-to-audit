package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Supplier;
import com.orderaudit.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    private static final String GSTIN = "33ABCDE1234F1Z5"; // FICTIONAL

    @Mock
    SupplierRepository repository;

    // The service records an audit row on every create; without this mock @InjectMocks passes null.
    @Mock
    AuditService audit;

    @InjectMocks
    SupplierService service;

    @Test
    void createsASupplier() {
        when(repository.existsByNameIgnoreCase("Flour Mill")).thenReturn(false);
        when(repository.existsByGstin(GSTIN)).thenReturn(false);
        when(repository.save(any(Supplier.class))).thenAnswer(call -> call.getArgument(0));

        Supplier s = service.create("Flour Mill", GSTIN, "33", null);

        assertThat(s.getName()).isEqualTo("Flour Mill");
        assertThat(s.getStateCode()).isEqualTo("33");
    }

    @Test
    void rejectsDuplicateName() {
        when(repository.existsByNameIgnoreCase("Flour Mill")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Flour Mill", null, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDuplicateGstin() {
        when(repository.existsByGstin(GSTIN)).thenReturn(true);

        assertThatThrownBy(() -> service.create("Other Mill", GSTIN, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBadStateCode() {
        assertThatThrownBy(() -> service.create("Flour Mill", null, "XX", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
