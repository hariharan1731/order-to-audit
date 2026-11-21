package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit test: the repository is a fake (mock), so no database is needed and the rules are tested alone. */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    ItemRepository repository;

    @InjectMocks
    ItemService service;

    @Test
    void createsFlourAsRawItemInGrams() {
        when(repository.existsByNameIgnoreCase("Flour")).thenReturn(false);
        when(repository.save(any(Item.class))).thenAnswer(call -> call.getArgument(0));

        Item flour = service.create("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);

        assertThat(flour.getName()).isEqualTo("Flour");
        assertThat(flour.getBaseUnit()).isEqualTo(Unit.GRAM);
        assertThat(flour.getHsnCode()).isNull();
    }

    @Test
    void trimsTheName() {
        when(repository.existsByNameIgnoreCase("Flour")).thenReturn(false);
        when(repository.save(any(Item.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.create("  Flour  ", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO).getName())
                .isEqualTo("Flour");
    }

    @Test
    void rejectsDuplicateNameAndSavesNothing() {
        when(repository.existsByNameIgnoreCase("Flour")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO))
                .isInstanceOf(DuplicateItemException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBaseUnitThatDoesNotSuitTheType() {
        assertThatThrownBy(() -> service.create("Murukku 200 g", ItemType.FINISHED, Unit.GRAM, null, new BigDecimal("5")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create("   ", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBadGstRates() {
        assertThatThrownBy(() -> service.create("X", ItemType.RAW, Unit.GRAM, null, new BigDecimal("101")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("X", ItemType.RAW, Unit.GRAM, null, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("X", ItemType.RAW, Unit.GRAM, null, new BigDecimal("5.555")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("X", ItemType.RAW, Unit.GRAM, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedHsnCode() {
        assertThatThrownBy(() -> service.create("X", ItemType.RAW, Unit.GRAM, "12AB", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
