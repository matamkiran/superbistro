package com.superbistro.daybook;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.superbistro.common.ApiException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes day-book entries (money in / out that isn't a sale). */
@Component
public class CashBook {
    public static final Set<String> METHODS = Set.of("CASH", "UPI", "CARD", "BANK", "CREDIT");

    private final JdbcTemplate jdbc;

    public CashBook(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param direction IN or OUT
     * @param method    CASH, UPI, CARD, BANK, or CREDIT (owed to a supplier, not paid yet)
     */
    public UUID add(UUID restaurantId, LocalDate day, String direction, String method, String category,
                    BigDecimal amount, String note, UUID purchaseId, UUID userId) {
        if (!"IN".equals(direction) && !"OUT".equals(direction)) throw ApiException.badRequest("Direction must be IN or OUT");
        if (!METHODS.contains(method)) throw ApiException.badRequest("Paid by must be Cash, UPI, Card, Bank or Credit");
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("Enter an amount above 0");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_entry (id, restaurant_id, business_day, direction, method, category, amount, note, purchase_id, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, restaurantId, day, direction, method, category == null || category.isBlank() ? "Other" : category.trim(),
            amount, note == null ? "" : note.trim(), purchaseId, userId, Timestamp.from(Instant.now()));
        return id;
    }
}
