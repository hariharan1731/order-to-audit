package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Customer;
import com.orderaudit.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** GSTINs below are FICTIONAL placeholders in the correct format (no checksum is checked). */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final String TN_GSTIN = "33ABCDE1234F1Z5"; // FICTIONAL
    private static final String KA_GSTIN = "29ABCDE1234F1Z5"; // FICTIONAL

    @Mock
    CustomerRepository repository;

    // The service records an audit row on every create; without this mock @InjectMocks passes null.
    @Mock
    AuditService audit;

    @InjectMocks
    CustomerService service;

    @Test
    void createsRetailerAInTamilNadu() {
        when(repository.existsByNameIgnoreCase("Retailer A")).thenReturn(false);
        when(repository.existsByGstin(TN_GSTIN)).thenReturn(false);
        when(repository.save(any(Customer.class))).thenAnswer(call -> call.getArgument(0));

        Customer a = service.create("Retailer A", TN_GSTIN, "33", "Trichy");

        assertThat(a.getStateCode()).isEqualTo("33");
        assertThat(a.getGstin()).isEqualTo(TN_GSTIN);
    }

    @Test
    void allowsAnUnregisteredDistributorBInKarnatakaWithoutGstin() {
        when(repository.existsByNameIgnoreCase("Distributor B")).thenReturn(false);
        when(repository.save(any(Customer.class))).thenAnswer(call -> call.getArgument(0));

        Customer b = service.create("Distributor B", "  ", "29", null);

        assertThat(b.getStateCode()).isEqualTo("29");
        assertThat(b.getGstin()).isNull();
    }

    @Test
    void trimsAndUpperCasesTheGstin() {
        when(repository.existsByNameIgnoreCase("Retailer A")).thenReturn(false);
        when(repository.existsByGstin(TN_GSTIN)).thenReturn(false);
        when(repository.save(any(Customer.class))).thenAnswer(call -> call.getArgument(0));

        Customer a = service.create(" Retailer A ", " " + TN_GSTIN.toLowerCase() + " ", "33", null);

        assertThat(a.getName()).isEqualTo("Retailer A");
        assertThat(a.getGstin()).isEqualTo(TN_GSTIN);
    }

    @Test
    void rejectsStateCodeThatIsNotTwoDigits() {
        assertThatThrownBy(() -> service.create("X", null, "TN", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("X", null, "3", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("X", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedGstin() {
        assertThatThrownBy(() -> service.create("X", "33ABC", "33", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsGstinWhoseStatePrefixDiffersFromTheStateCode() {
        assertThatThrownBy(() -> service.create("X", KA_GSTIN, "33", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsDuplicateNameAndSavesNothing() {
        when(repository.existsByNameIgnoreCase("Retailer A")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Retailer A", null, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDuplicateGstinAndSavesNothing() {
        when(repository.existsByGstin(TN_GSTIN)).thenReturn(true);

        assertThatThrownBy(() -> service.create("Another Retailer", TN_GSTIN, "33", null))
                .isInstanceOf(DuplicatePartyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create("   ", null, "33", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
