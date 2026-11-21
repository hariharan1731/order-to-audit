package com.orderaudit.repository;

import com.orderaudit.domain.Recipe;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {
    boolean existsByNameIgnoreCase(String name);
}
