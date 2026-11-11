package com.superbistro;

import java.math.BigDecimal;

import com.superbistro.auth.AppUser;
import com.superbistro.auth.AppUserRepository;
import com.superbistro.auth.Role;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Tests need separate restaurants to check that data never mixes; the app itself has no sign-up page. */
final class TestOwners {
    private TestOwners() {
    }

    static void create(ApplicationContext ctx, String restaurant, String email, String password) {
        Restaurant r = ctx.getBean(RestaurantRepository.class).save(new Restaurant(restaurant, new BigDecimal("5.00")));
        ctx.getBean(AppUserRepository.class).save(new AppUser(r.getId(), email, "Owner",
            ctx.getBean(PasswordEncoder.class).encode(password), Role.OWNER));
    }
}
