package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import com.orderaudit.domain.PackBasis;
import com.orderaudit.domain.PackConfig;
import com.orderaudit.domain.PackLine;
import com.orderaudit.domain.PackLineSpec;
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.PackConfigRepository;
import com.orderaudit.repository.PackLineRepository;
import com.orderaudit.repository.RecipeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackConfigServiceTest {

    @Mock PackConfigRepository configs;
    @Mock PackLineRepository lines;
    @Mock RecipeRepository recipes;
    @Mock ItemRepository items;
    @InjectMocks PackConfigService service;

    private final Item murukku = new Item("Murukku 200 g", ItemType.FINISHED, Unit.PACKET, null, new BigDecimal("5"));
    private final Item pouch = new Item("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);
    private final Item carton = new Item("Carton", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);
    private final Item flour = new Item("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
    private final Recipe recipe = new Recipe("Murukku base", 40_000, null);

    private static List<PackLineSpec> pouchAndCarton() {
        return List.of(new PackLineSpec(4, 1, PackBasis.PER_PACKET), new PackLineSpec(5, 1, PackBasis.PER_CARTON));
    }

    @Test
    void createsAConfigWithItsPackagingLines() {
        when(items.findById(10L)).thenReturn(Optional.of(murukku));
        when(recipes.findById(7L)).thenReturn(Optional.of(recipe));
        when(items.findById(4L)).thenReturn(Optional.of(pouch));
        when(items.findById(5L)).thenReturn(Optional.of(carton));
        when(configs.save(any(PackConfig.class))).thenAnswer(call -> call.getArgument(0));

        PackConfig config = service.create(10, 7, 200, 20, pouchAndCarton());

        assertThat(config.getPackSizeGrams()).isEqualTo(200);
        assertThat(config.getPacketsPerCarton()).isEqualTo(20);
        verify(lines, times(2)).save(any(PackLine.class));
    }

    @Test
    void rejectsAFinishedItemThatIsNotFinished() {
        when(items.findById(1L)).thenReturn(Optional.of(flour));

        assertThatThrownBy(() -> service.create(1, 7, 200, 20, pouchAndCarton()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(configs, never()).save(any());
    }

    @Test
    void rejectsAMissingRecipe() {
        when(items.findById(10L)).thenReturn(Optional.of(murukku));

        assertThatThrownBy(() -> service.create(10, 7, 200, 20, pouchAndCarton()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(configs, never()).save(any());
    }

    @Test
    void rejectsARawItemUsedAsPackaging() {
        when(items.findById(10L)).thenReturn(Optional.of(murukku));
        when(recipes.findById(7L)).thenReturn(Optional.of(recipe));
        when(items.findById(1L)).thenReturn(Optional.of(flour));

        assertThatThrownBy(() -> service.create(10, 7, 200, 20, List.of(new PackLineSpec(1, 1, PackBasis.PER_PACKET))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(configs, never()).save(any());
    }

    @Test
    void rejectsAFinishedItemThatAlreadyHasAConfig() {
        when(items.findById(10L)).thenReturn(Optional.of(murukku));
        when(recipes.findById(7L)).thenReturn(Optional.of(recipe));
        when(items.findById(4L)).thenReturn(Optional.of(pouch));
        when(items.findById(5L)).thenReturn(Optional.of(carton));
        when(configs.existsByFinishedItemId(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(10, 7, 200, 20, pouchAndCarton()))
                .isInstanceOf(DuplicateRecordException.class);
        verify(configs, never()).save(any());
    }

    @Test
    void rejectsNonPositiveSizesAndEmptyPackaging() {
        assertThatThrownBy(() -> service.create(10, 7, 0, 20, pouchAndCarton()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(10, 7, 200, 0, pouchAndCarton()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(configs, lines, recipes, items);
    }
}
