package com.superbistro.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in person, kept in the HTTP session. restaurantId is the tenant key:
 * every query in the app is scoped by it, so one restaurant never sees another's data.
 */
public final class CurrentUser implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final UUID restaurantId;
    private final String email;
    private final String name;
    private final Role role;
    private final String passwordHash;
    private final boolean platformAdmin;

    public CurrentUser(AppUser u) {
        this.id = u.getId();
        this.restaurantId = u.getRestaurantId();
        this.email = u.getEmail();
        this.name = u.getName();
        this.role = u.getRole();
        this.passwordHash = u.getPasswordHash();
        this.platformAdmin = u.isPlatformAdmin();
    }

    public UUID id() { return id; }
    public UUID restaurantId() { return restaurantId; }
    public String email() { return email; }
    public String name() { return name; }
    public Role role() { return role; }
    /** Main admin of the installation: backups and system settings. */
    public boolean platformAdmin() { return platformAdmin; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return platformAdmin
            ? List.of(new SimpleGrantedAuthority("ROLE_" + role.name()), new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
            : List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public String getUsername() { return email; }
}
