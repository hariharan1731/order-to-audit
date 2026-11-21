package com.orderaudit.repository;

import com.orderaudit.domain.Item;
import com.orderaudit.domain.ItemType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring writes the implementation of this interface for us.
 * The method names are the queries: existsByNameIgnoreCase = "is there a row whose name matches, ignoring case?".
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    boolean existsByNameIgnoreCase(String name);

    List<Item> findByItemTypeAndActiveTrueOrderByNameAsc(ItemType itemType);
}
