package com.superbistro.setup;

/**
 * The first restaurant and owner entered on the setup page, handed to the main app, which creates
 * them once its database tables exist. The password is already BCrypt-hashed.
 */
public record BootstrapOwner(String restaurantName, String ownerName, String email, String passwordHash,
                             boolean readyMade) {
    /** The entered owner from the setup page. */
    public BootstrapOwner(String restaurantName, String ownerName, String email, String passwordHash) {
        this(restaurantName, ownerName, email, passwordHash, false);
    }

    private static volatile BootstrapOwner pending;

    public static void set(BootstrapOwner owner) {
        pending = owner;
    }

    /** Returns the pending owner once, then forgets it. */
    public static BootstrapOwner take() {
        BootstrapOwner o = pending;
        pending = null;
        return o;
    }
}
