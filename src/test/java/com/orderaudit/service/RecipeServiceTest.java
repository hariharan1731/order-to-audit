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
import com.orderaudit.domain.Recipe;
import com.orderaudit.domain.RecipeLine;
import com.orderaudit.domain.RecipeLineSpec;
import com.orderaudit.domain.Unit;
import com.orderaudit.repository.ItemRepository;
import com.orderaudit.repository.RecipeLineRepository;
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
class RecipeServiceTest {

    @Mock RecipeRepository recipes;
    @Mock RecipeLineRepository lines;
    @Mock ItemRepository items;
    @InjectMocks RecipeService service;

    private final Item flour = new Item("Flour", ItemType.RAW, Unit.GRAM, null, BigDecimal.ZERO);
    private final Item oil = new Item("Oil", ItemType.RAW, Unit.MILLILITRE, null, BigDecimal.ZERO);
    private final Item pouch = new Item("Pouch 200 g", ItemType.PACKAGING, Unit.PIECE, null, BigDecimal.ZERO);

    private static List<RecipeLineSpec> flourAndOil() {
        return List.of(new RecipeLineSpec(1, 40_000), new RecipeLineSpec(2, 10_000));
    }

    @Test
    void createsARecipeAndItsLines() {
        when(items.findById(1L)).thenReturn(Optional.of(flour));
        when(items.findById(2L)).thenReturn(Optional.of(oil));
        when(recipes.save(any(Recipe.class))).thenAnswer(call -> call.getArgument(0));

        Recipe recipe = service.create("  Murukku base ", 40_000, null, flourAndOil());

        assertThat(recipe.getName()).isEqualTo("Murukku base");
        assertThat(recipe.getBulkYieldGrams()).isEqualTo(40_000);
        verify(lines, times(2)).save(any(RecipeLine.class));
    }

    @Test
    void rejectsAnItemThatIsNotRaw() {
        when(items.findById(4L)).thenReturn(Optional.of(pouch));

        assertThatThrownBy(() -> service.create("R", 1000, null, List.of(new RecipeLineSpec(4, 5))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recipes, never()).save(any());
    }

    @Test
    void rejectsAnUnknownItem() {
        assertThatThrownBy(() -> service.create("R", 1000, null, List.of(new RecipeLineSpec(99, 5))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recipes, never()).save(any());
    }

    @Test
    void rejectsTheSameItemTwice() {
        when(items.findById(1L)).thenReturn(Optional.of(flour));

        assertThatThrownBy(() -> service.create("R", 1000, null,
                List.of(new RecipeLineSpec(1, 5), new RecipeLineSpec(1, 6))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recipes, never()).save(any());
    }

    @Test
    void rejectsNoLinesAndNonPositiveQuantities() {
        assertThatThrownBy(() -> service.create("R", 1000, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("R", 1000, null, List.of(new RecipeLineSpec(1, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(recipes, lines);
    }

    @Test
    void rejectsBadYieldToleranceAndName() {
        assertThatThrownBy(() -> service.create("R", 0, null, flourAndOil()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("R", 1000, new BigDecimal("101"), flourAndOil()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("   ", 1000, null, flourAndOil()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(recipes, lines, items);
    }

    @Test
    void rejectsADuplicateRecipeNameAndSavesNothing() {
        when(items.findById(1L)).thenReturn(Optional.of(flour));
        when(items.findById(2L)).thenReturn(Optional.of(oil));
        when(recipes.existsByNameIgnoreCase("Murukku base")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Murukku base", 40_000, null, flourAndOil()))
                .isInstanceOf(DuplicateRecordException.class);
        verify(recipes, never()).save(any());
        verify(lines, never()).save(any());
    }
}
