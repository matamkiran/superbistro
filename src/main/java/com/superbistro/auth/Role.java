package com.superbistro.auth;

/**
 * OWNER: everything for the restaurant (menu, prices, staff, settings, stock, day book).
 * MANAGER: store manager: stock, purchases and the day book, plus taking orders.
 * STAFF: takes orders and moves them through the kitchen.
 */
public enum Role {
    OWNER,
    MANAGER,
    STAFF
}
