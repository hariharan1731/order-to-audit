package com.orderaudit.repository;

import com.orderaudit.domain.RecipeLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeLineRepository extends JpaRepository<RecipeLine, Long> {
    List<RecipeLine> findByRecipeId(Long recipeId);

    boolean existsByItemId(Long itemId);
}
