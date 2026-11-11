package com.superbistro.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.superbistro.auth.AppUser;
import com.superbistro.auth.AppUserRepository;
import com.superbistro.auth.Role;
import com.superbistro.menu.MenuItem;
import com.superbistro.menu.MenuItemRepository;
import com.superbistro.setup.BootstrapOwner;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final MenuItemRepository menu;
    private final boolean readyMade;
    private final boolean resetAdmin;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public BootstrapRunner(AppUserRepository users, RestaurantRepository restaurants, MenuItemRepository menu,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           @Value("${READY_MADE:false}") boolean readyMade,
                           @Value("${superbistro.reset-admin:false}") boolean resetAdmin) {
        this.users = users;
        this.restaurants = restaurants;
        this.menu = menu;
        this.jdbc = jdbc;
        this.readyMade = readyMade;
        this.resetAdmin = resetAdmin;
    }

    static final String ADDRESS = "Sri Vara Nilayam, 148, 2nd Cross Rd, Sahakar Nagar, H A Farm Post, "
        + "Maruthi Nagar, Dasarahalli, Bengaluru, Karnataka 560024";
    static final String PHONE = "08147284442";

    /** Fills in Super Bistro's address and phone on the bill when they were left empty. */
    private void fillBillDetailsIfEmpty() {
        List<Restaurant> all = restaurants.findAll();
        if (all.size() != 1) return;
        Restaurant r = all.get(0);
        boolean noAddress = r.getAddress() == null || r.getAddress().isBlank();
        boolean noPhone = r.getPhone() == null || r.getPhone().isBlank();
        if (!noAddress && !noPhone) return;
        r.setBillDetails(noAddress ? ADDRESS : r.getAddress(), noPhone ? PHONE : r.getPhone(),
            r.getGstin() == null ? "" : r.getGstin(), r.getBillFooter() == null ? "" : r.getBillFooter());
        restaurants.save(r);
        log.info("Filled in the Super Bistro address/phone for the bill");
    }

    /**
     * Installs set up with an earlier version got no menu: if Super Bistro has no dishes and no orders
     * yet, load its menu once.
     */
    private void loadMenuIfEmpty() {
        List<Restaurant> all = restaurants.findAll();
        if (all.size() != 1) return;
        Restaurant r = all.get(0);
        Integer dishes = jdbc.queryForObject("SELECT COUNT(*) FROM menu_item WHERE restaurant_id = ?", Integer.class, r.getId());
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE restaurant_id = ?", Integer.class, r.getId());
        if (dishes == null || dishes > 0 || orders == null || orders > 0) return;
        List<MenuItem> items = superBistroMenu(r);
        menu.saveAll(items);
        log.info("Loaded {} dishes of the Super Bistro menu (the menu was empty)", items.size());
    }

    /** Puts the main admin's password back to the ready-made one; they must choose a new one at sign-in. */
    private void resetAdminPassword() {
        AppUser admin = users.findAll().stream().filter(AppUser::isPlatformAdmin)
            .min(java.util.Comparator.comparing(AppUser::getCreatedAt)).orElse(null);
        if (admin == null) {
            log.warn("Password reset: no main admin found");
            return;
        }
        admin.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .encode(com.superbistro.setup.Launcher.DEFAULT_PASSWORD));
        admin.setMustChangePassword(true);
        users.save(admin);
        jdbc.update("DELETE FROM spring_session"); // everyone signs in again
        System.out.println();
        System.out.println("  Password reset. Sign in with  " + admin.getEmail() + " / "
            + com.superbistro.setup.Launcher.DEFAULT_PASSWORD + "  and choose a new password. All data is kept.");
        System.out.println();
        log.info("Password reset for main admin {}", admin.getEmail());
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (resetAdmin) {
            resetAdminPassword();
        }
        BootstrapOwner b = BootstrapOwner.take();
        if (b == null && users.count() == 0) {
            // No login yet (first start, an interrupted first start, or a server install): Super Bistro,
            // its menu and the ready-made admin login.
            b = new BootstrapOwner("Super Bistro", "Admin", com.superbistro.setup.Launcher.DEFAULT_LOGIN,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(com.superbistro.setup.Launcher.DEFAULT_PASSWORD), true);
        }
        if (b == null) {
            loadMenuIfEmpty();
            fillBillDetailsIfEmpty();
            return;
        }
        if (users.existsByEmail(b.email())) {
            log.info("Setup: {} already has an account; keeping it", b.email());
            return;
        }
        Restaurant r = new Restaurant(b.restaurantName(), BigDecimal.ZERO);
        if (b.readyMade()) {
            r.setBillDetails(ADDRESS, PHONE, "", "");
        }
        r = restaurants.save(r);
        AppUser owner = new AppUser(r.getId(), b.email(), b.ownerName(), b.passwordHash(), Role.OWNER);
        owner.setPlatformAdmin(true);
        owner.setMustChangePassword(b.readyMade());
        users.save(owner);
        if (b.readyMade()) {
            List<MenuItem> items = superBistroMenu(r);
            menu.saveAll(items);
            log.info("Setup: loaded {} dishes of the Super Bistro menu", items.size());
        }
        log.info("Setup: created restaurant '{}' with main admin {}", r.getName(), b.email());
    }

    /** The Super Bistro menu packed in the app (seed/super-bistro-menu.csv: Name,Category,Price,Veg). */
    private static List<MenuItem> superBistroMenu(Restaurant r) {
        List<MenuItem> out = new ArrayList<>();
        try (InputStream in = BootstrapRunner.class.getResourceAsStream("/seed/super-bistro-menu.csv")) {
            if (in == null) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            br.readLine(); // header
            for (String line; (line = br.readLine()) != null; ) {
                String[] c = line.split(",");
                if (c.length < 4 || c[0].isBlank()) continue;
                out.add(new MenuItem(r.getId(), c[0].trim(), new BigDecimal(c[2].trim()), c[1].trim(),
                    !c[3].trim().equalsIgnoreCase("no")));
            }
        } catch (IOException e) {
            log.warn("Couldn't load the Super Bistro menu: {}", e.getMessage());
        }
        return out;
    }
}
