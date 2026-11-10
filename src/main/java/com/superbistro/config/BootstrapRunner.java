package com.superbistro.config;

import java.math.BigDecimal;

import com.superbistro.auth.AppUser;
import com.superbistro.auth.AppUserRepository;
import com.superbistro.auth.Role;
import com.superbistro.setup.BootstrapOwner;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the restaurant and main admin entered on the first-time setup page. */
@Component
public class BootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppUserRepository users;
    private final RestaurantRepository restaurants;

    public BootstrapRunner(AppUserRepository users, RestaurantRepository restaurants) {
        this.users = users;
        this.restaurants = restaurants;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        BootstrapOwner b = BootstrapOwner.take();
        if (b == null) {
            return;
        }
        if (users.existsByEmail(b.email())) {
            log.info("Setup: {} already has an account; keeping it", b.email());
            return;
        }
        Restaurant r = restaurants.save(new Restaurant(b.restaurantName(), BigDecimal.ZERO));
        AppUser owner = new AppUser(r.getId(), b.email(), b.ownerName(), b.passwordHash(), Role.OWNER);
        owner.setPlatformAdmin(true);
        users.save(owner);
        log.info("Setup: created restaurant '{}' with main admin {}", r.getName(), b.email());
    }
}
