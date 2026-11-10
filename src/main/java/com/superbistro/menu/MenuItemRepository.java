package com.superbistro.menu;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {
    List<MenuItem> findByRestaurantIdOrderByCategoryAscCreatedAtAsc(UUID restaurantId);

    Optional<MenuItem> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    List<MenuItem> findByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);

}
