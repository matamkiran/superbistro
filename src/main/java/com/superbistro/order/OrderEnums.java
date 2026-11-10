package com.superbistro.order;

/** Value sets for orders. Stored as their names (VARCHAR) in the database. */
public final class OrderEnums {
    private OrderEnums() {
    }

    /** Where the order came from. */
    public enum Source { OFFLINE, ZOMATO, SWIGGY }

    /** How an offline order is served. Zomato/Swiggy orders are always DELIVERY. */
    public enum Type { DINE_IN, TAKEAWAY, DELIVERY }

    /** PLATFORM = already paid to Zomato/Swiggy. UNPAID = settle later (e.g. running table bill). */
    public enum Payment { CASH, UPI, CARD, PLATFORM, UNPAID }

    /** Kitchen flow: NEW -> PREPARING -> READY -> COMPLETED, or CANCELLED at any point. */
    public enum Status { NEW, PREPARING, READY, COMPLETED, CANCELLED }
}
