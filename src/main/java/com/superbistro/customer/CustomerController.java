package com.superbistro.customer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers, worked out from the orders: everyone who gave a mobile number. The same number
 * written differently ("98765 43210", "+91 9876543210") is the same customer (last 10 digits).
 * Cancelled orders don't count as visits.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    /** One customer: latest non-empty name and address, visits and money spent. */
    public record Customer(String key, String phone, String name, String address, int visits, BigDecimal spent,
                           long firstAt, long lastAt) {
    }

    public record PastOrder(String id, int no, String day, String source, String type, BigDecimal total,
                            String status, long createdAt, String items) {
    }

    /** What the till shows after a mobile number is typed. */
    public record Lookup(Customer customer, List<String> favourites, List<PastOrder> recent) {
    }

    private record Row(UUID id, int no, String day, String source, String type, String name, String phone,
                       String address, BigDecimal total, String status, long at) {
    }

    private final JdbcTemplate jdbc;

    public CustomerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Last 10 digits of a mobile number, or "" when it has fewer than 10. */
    public static String key(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() < 10 ? "" : digits.substring(digits.length() - 10);
    }

    /** At the till (any role): who is this number? */
    @GetMapping("/lookup")
    public Lookup lookup(@AuthenticationPrincipal CurrentUser me, @RequestParam String phone) {
        String k = key(phone);
        if (k.isEmpty()) return new Lookup(null, List.of(), List.of());
        List<Row> rows = rows(me.restaurantId(), k);
        Customer c = summarise(k, rows);
        if (c == null) return new Lookup(null, List.of(), List.of());
        List<Row> kept = rows.stream().filter(r -> !"CANCELLED".equals(r.status())).toList();
        List<String> favourites = kept.isEmpty() ? List.of() : jdbc.queryForList(
            "SELECT name FROM order_item WHERE order_id IN (" + String.join(",", kept.stream().map(r -> "?").toList()) + ")"
                + " GROUP BY name ORDER BY SUM(qty) DESC, name LIMIT 3",
            String.class, kept.stream().map(Row::id).toArray());
        return new Lookup(c, favourites, toPast(rows.stream().limit(5).toList()));
    }

    /** The customer list (owner, manager), most recent visit first. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Customer> list(@AuthenticationPrincipal CurrentUser me) {
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row r : rows(me.restaurantId(), null)) {
            String k = key(r.phone());
            if (!k.isEmpty()) byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        List<Customer> out = new ArrayList<>();
        byKey.forEach((k, rows) -> {
            Customer c = summarise(k, rows);
            if (c != null) out.add(c);
        });
        out.sort(Comparator.comparingLong(Customer::lastAt).reversed());
        return out;
    }

    /** One customer's orders (owner, manager), newest first. */
    @GetMapping("/{phone}/orders")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<PastOrder> orders(@AuthenticationPrincipal CurrentUser me, @PathVariable String phone) {
        String k = key(phone);
        if (k.isEmpty()) throw ApiException.badRequest("Enter a 10-digit mobile number");
        return toPast(rows(me.restaurantId(), k));
    }

    /** Orders with a phone number, newest first; only this customer's when k is given. */
    private List<Row> rows(UUID restaurantId, String k) {
        // A cheap SQL filter on the last 4 digits, then the exact 10-digit match in Java.
        String sql = "SELECT id, order_no, business_day, source, order_type, customer, phone, address, total, status, created_at"
            + " FROM customer_order WHERE restaurant_id = ? AND phone <> ''" + (k == null ? "" : " AND phone LIKE ?")
            + " ORDER BY created_at DESC";
        Object[] args = k == null ? new Object[] {restaurantId} : new Object[] {restaurantId, "%" + k.substring(6)};
        List<Row> rows = jdbc.query(sql, (rs, i) -> {
            Timestamp at = rs.getTimestamp("created_at");
            return new Row(rs.getObject("id", UUID.class), rs.getInt("order_no"), rs.getString("business_day"),
                rs.getString("source"), rs.getString("order_type"), rs.getString("customer"), rs.getString("phone"),
                rs.getString("address"), rs.getBigDecimal("total"), rs.getString("status"), at.getTime());
        }, args);
        return k == null ? rows : rows.stream().filter(r -> k.equals(key(r.phone()))).toList();
    }

    /** rows: newest first. null when every order was cancelled. */
    private static Customer summarise(String k, List<Row> rows) {
        List<Row> kept = rows.stream().filter(r -> !"CANCELLED".equals(r.status())).toList();
        if (kept.isEmpty()) return null;
        String name = kept.stream().map(Row::name).filter(n -> n != null && !n.isBlank()).findFirst().orElse("");
        String address = kept.stream().map(Row::address).filter(a -> a != null && !a.isBlank()).findFirst().orElse("");
        BigDecimal spent = kept.stream().map(Row::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Customer(k, kept.get(0).phone(), name, address, kept.size(), spent,
            kept.get(kept.size() - 1).at(), kept.get(0).at());
    }

    private List<PastOrder> toPast(List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, StringBuilder> items = new LinkedHashMap<>();
        jdbc.query("SELECT order_id, qty, name FROM order_item WHERE order_id IN ("
                + String.join(",", rows.stream().map(r -> "?").toList()) + ") ORDER BY line_no",
            rs -> {
                StringBuilder sb = items.computeIfAbsent(rs.getObject("order_id", UUID.class), x -> new StringBuilder());
                if (sb.length() > 0) sb.append(", ");
                sb.append(rs.getInt("qty")).append("× ").append(rs.getString("name"));
            }, rows.stream().map(Row::id).toArray());
        return rows.stream().map(r -> new PastOrder(r.id().toString(), r.no(), r.day(), r.source(), r.type(), r.total(),
            r.status(), r.at(), items.getOrDefault(r.id(), new StringBuilder()).toString())).toList();
    }
}
