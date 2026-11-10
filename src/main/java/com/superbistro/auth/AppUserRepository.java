package com.superbistro.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    List<AppUser> findByRestaurantIdOrderByCreatedAt(UUID restaurantId);

    Optional<AppUser> findByIdAndRestaurantId(UUID id, UUID restaurantId);
}
