package com.superbistro.daybook;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily cash flow: sales by payment method, money paid out and taken in, and the cash drawer
 * check (opening + cash sales + cash in - cash out = expected; compared with the counted cash).
 */
@RestController
@RequestMapping("/api/daybook")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class DayBookController {
    private static final BigDecimal Z = BigDecimal.ZERO;

    public record EntryRequest(
        LocalDate day,
        @NotNull @Pattern(regexp = "IN|OUT") String direction,
        @NotNull @Pattern(regexp = "CASH|UPI|CARD|BANK|CREDIT") String method,
        @Size(max = 40) String category,
        @NotNull @DecimalMin(value = "0.01", message = "must be above 0") BigDecimal amount,
        @Size(max = 200) String note) {
    }

    public record DrawerRequest(@DecimalMin("0") BigDecimal openingCash, @DecimalMin("0") BigDecimal countedCash) {
    }

    public record Entry(String id, String direction, String method, String category, BigDecimal amount, String note,
                        boolean fromPurchase, long createdAt, String by) {
    }

    private final JdbcTemplate jdbc;
    private final CashBook cashBook;
    private final AppProperties props;

    public DayBookController(JdbcTemplate jdbc, CashBook cashBook, AppProperties props) {
        this.jdbc = jdbc;
        this.cashBook = cashBook;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> day(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) LocalDate day) {
        LocalDate d = day == null ? LocalDate.now(props.zone()) : day;
        UUID r = me.restaurantId();

        Map<String, BigDecimal> byPayment = new LinkedHashMap<>();
        for (String p : List.of("CASH", "UPI", "CARD", "PLATFORM", "UNPAID")) byPayment.put(p, Z);
        Map<String, Long> countByPayment = new LinkedHashMap<>();
        jdbc.query("SELECT payment, COUNT(*), COALESCE(SUM(total), 0) FROM customer_order WHERE restaurant_id = ? AND business_day = ?"
                + " AND status <> 'CANCELLED' GROUP BY payment", rs -> {
            byPayment.put(rs.getString(1), rs.getBigDecimal(3));
            countByPayment.put(rs.getString(1), rs.getLong(2));
        }, r, d);
        Map<String, BigDecimal> bySource = new LinkedHashMap<>();
        for (String s : List.of("OFFLINE", "ZOMATO", "SWIGGY")) bySource.put(s, Z);
        jdbc.query("SELECT source, COALESCE(SUM(total), 0) FROM customer_order WHERE restaurant_id = ? AND business_day = ?"
            + " AND status <> 'CANCELLED' GROUP BY source", rs -> { bySource.put(rs.getString(1), rs.getBigDecimal(2)); }, r, d);
        long orders = countByPayment.values().stream().mapToLong(Long::longValue).sum();
        Long cancelled = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE restaurant_id = ? AND business_day = ? AND status = 'CANCELLED'",
            Long.class, r, d);
        BigDecimal sales = byPayment.values().stream().reduce(Z, BigDecimal::add);

        List<Entry> entries = jdbc.query("SELECT e.*, u.name AS by_name FROM cash_entry e LEFT JOIN app_user u ON u.id = e.created_by"
                + " WHERE e.restaurant_id = ? AND e.business_day = ? ORDER BY e.created_at",
            (rs, i) -> new Entry(rs.getString("id"), rs.getString("direction"), rs.getString("method"), rs.getString("category"),
                rs.getBigDecimal("amount"), rs.getString("note"), rs.getString("purchase_id") != null,
                rs.getTimestamp("created_at").getTime(), rs.getString("by_name")), r, d);

        BigDecimal cashIn = Z, cashOut = Z, otherIn = Z, paidOut = Z, onCredit = Z;
        Map<String, BigDecimal> outByCategory = new TreeMap<>();
        for (Entry e : entries) {
            boolean cash = "CASH".equals(e.method());
            if ("IN".equals(e.direction())) {
                if (cash) cashIn = cashIn.add(e.amount());
                else otherIn = otherIn.add(e.amount());
            } else if ("CREDIT".equals(e.method())) {
                onCredit = onCredit.add(e.amount());
            } else {
                paidOut = paidOut.add(e.amount());
                outByCategory.merge(e.category(), e.amount(), BigDecimal::add);
                if (cash) cashOut = cashOut.add(e.amount());
            }
        }

        BigDecimal opening = null, counted = null;
        List<BigDecimal[]> dc = jdbc.query("SELECT opening_cash, counted_cash FROM day_close WHERE restaurant_id = ? AND business_day = ?",
            (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, r, d);
        if (!dc.isEmpty()) {
            opening = dc.get(0)[0];
            counted = dc.get(0)[1];
        }
        // The last counted cash before this day is offered as this day's opening cash.
        List<BigDecimal> prevCount = jdbc.query("SELECT counted_cash FROM day_close WHERE restaurant_id = ? AND business_day < ? AND counted_cash IS NOT NULL"
            + " ORDER BY business_day DESC LIMIT 1", (rs, i) -> rs.getBigDecimal(1), r, d);

        BigDecimal open = opening == null ? Z : opening;
        BigDecimal expected = open.add(byPayment.get("CASH")).add(cashIn).subtract(cashOut);
        Map<String, Object> drawer = new LinkedHashMap<>();
        drawer.put("opening", opening);
        drawer.put("suggestedOpening", prevCount.isEmpty() ? null : prevCount.get(0));
        drawer.put("cashSales", byPayment.get("CASH"));
        drawer.put("cashIn", cashIn);
        drawer.put("cashOut", cashOut);
        drawer.put("expected", expected);
        drawer.put("counted", counted);
        drawer.put("difference", counted == null ? null : counted.subtract(expected));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", d.toString());
        out.put("today", LocalDate.now(props.zone()).toString());
        out.put("orders", orders);
        out.put("cancelled", cancelled);
        // Every cancelled bill with who cancelled it and why, so nothing disappears quietly.
        out.put("cancelledOrders", jdbc.query(
            "SELECT o.order_no, o.total, o.cancel_reason, o.cancelled_at, o.created_at, u.name AS by_name FROM customer_order o"
                + " LEFT JOIN app_user u ON u.id = o.cancelled_by WHERE o.restaurant_id = ? AND o.business_day = ? AND o.status = 'CANCELLED'"
                + " ORDER BY o.order_no",
            (rs, i) -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("no", rs.getInt("order_no"));
                c.put("total", rs.getBigDecimal("total"));
                c.put("reason", rs.getString("cancel_reason"));
                java.sql.Timestamp at = rs.getTimestamp("cancelled_at");
                c.put("at", at == null ? rs.getTimestamp("created_at").getTime() : at.getTime());
                c.put("by", rs.getString("by_name") == null ? "" : rs.getString("by_name"));
                return c;
            }, me.restaurantId(), d));
        out.put("sales", sales);
        out.put("received", sales.subtract(byPayment.get("UNPAID")));
        out.put("byPayment", byPayment);
        out.put("countByPayment", countByPayment);
        out.put("bySource", bySource);
        out.put("paidOut", paidOut);
        out.put("otherIn", otherIn.add(cashIn));
        out.put("onCredit", onCredit);
        out.put("outByCategory", outByCategory);
        out.put("net", sales.subtract(byPayment.get("UNPAID")).add(otherIn).add(cashIn).subtract(paidOut));
        out.put("drawer", drawer);
        out.put("entries", entries);
        return out;
    }

    /** One row per day: sales by payment, money paid out, and what is left. */
    @GetMapping("/summary")
    public List<Map<String, Object>> summary(@AuthenticationPrincipal CurrentUser me, @RequestParam(defaultValue = "14") int days) {
        int n = Math.max(1, Math.min(days, 92));
        LocalDate to = LocalDate.now(props.zone());
        LocalDate from = to.minusDays(n - 1L);
        Map<LocalDate, Map<String, Object>> rows = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d.toString());
            row.put("orders", 0L);
            for (String k : List.of("sales", "cash", "upi", "card", "online", "unpaid", "paidOut", "cashOut", "otherIn")) row.put(k, Z);
            rows.put(d, row);
        }
        jdbc.query("SELECT business_day, payment, COUNT(*), COALESCE(SUM(total), 0) FROM customer_order WHERE restaurant_id = ?"
                + " AND business_day BETWEEN ? AND ? AND status <> 'CANCELLED' GROUP BY business_day, payment", rs -> {
            Map<String, Object> row = rows.get(rs.getDate(1).toLocalDate());
            if (row == null) return;
            String key = switch (rs.getString(2)) {
                case "CASH" -> "cash"; case "UPI" -> "upi"; case "CARD" -> "card"; case "PLATFORM" -> "online"; default -> "unpaid";
            };
            row.put(key, ((BigDecimal) row.get(key)).add(rs.getBigDecimal(4)));
            row.put("sales", ((BigDecimal) row.get("sales")).add(rs.getBigDecimal(4)));
            row.put("orders", (Long) row.get("orders") + rs.getLong(3));
        }, me.restaurantId(), from, to);
        jdbc.query("SELECT business_day, direction, method, COALESCE(SUM(amount), 0) FROM cash_entry WHERE restaurant_id = ?"
                + " AND business_day BETWEEN ? AND ? AND method <> 'CREDIT' GROUP BY business_day, direction, method", rs -> {
            Map<String, Object> row = rows.get(rs.getDate(1).toLocalDate());
            if (row == null) return;
            BigDecimal amt = rs.getBigDecimal(4);
            if ("OUT".equals(rs.getString(2))) {
                row.put("paidOut", ((BigDecimal) row.get("paidOut")).add(amt));
                if ("CASH".equals(rs.getString(3))) row.put("cashOut", ((BigDecimal) row.get("cashOut")).add(amt));
            } else {
                row.put("otherIn", ((BigDecimal) row.get("otherIn")).add(amt));
            }
        }, me.restaurantId(), from, to);
        jdbc.query("SELECT business_day, counted_cash FROM day_close WHERE restaurant_id = ? AND business_day BETWEEN ? AND ?", rs -> {
            Map<String, Object> row = rows.get(rs.getDate(1).toLocalDate());
            if (row != null) row.put("counted", rs.getBigDecimal(2));
        }, me.restaurantId(), from, to);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows.values()) {
            BigDecimal received = ((BigDecimal) row.get("sales")).subtract((BigDecimal) row.get("unpaid")).add((BigDecimal) row.get("otherIn"));
            row.put("net", received.subtract((BigDecimal) row.get("paidOut")));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/entries")
    @Transactional
    public Map<String, Object> addEntry(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody EntryRequest e) {
        LocalDate d = e.day() == null ? LocalDate.now(props.zone()) : e.day();
        if (d.isAfter(LocalDate.now(props.zone()))) throw ApiException.badRequest("You can't add money for a future day");
        cashBook.add(me.restaurantId(), d, e.direction(), e.method(), e.category(), e.amount(), e.note(), null, me.id());
        return day(me, d);
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteEntry(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        List<String> p = jdbc.query("SELECT purchase_id FROM cash_entry WHERE id = ? AND restaurant_id = ?",
            (rs, i) -> String.valueOf(rs.getString(1)), id, me.restaurantId());
        if (p.isEmpty()) throw ApiException.notFound("Entry");
        if (!"null".equals(p.get(0))) throw ApiException.badRequest("This payment belongs to a store purchase and can't be deleted here");
        jdbc.update("DELETE FROM cash_entry WHERE id = ?", id);
    }

    /** Opening cash in the morning, counted cash at closing. Send only the one you are setting. */
    @PutMapping("/{day}/drawer")
    @Transactional
    public Map<String, Object> drawer(@AuthenticationPrincipal CurrentUser me, @PathVariable LocalDate day, @Valid @RequestBody DrawerRequest req) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM day_close WHERE restaurant_id = ? AND business_day = ?",
            Integer.class, me.restaurantId(), day);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO day_close (restaurant_id, business_day) VALUES (?, ?)", me.restaurantId(), day);
        }
        if (req.openingCash() != null) {
            jdbc.update("UPDATE day_close SET opening_cash = ? WHERE restaurant_id = ? AND business_day = ?", req.openingCash(), me.restaurantId(), day);
        }
        if (req.countedCash() != null) {
            jdbc.update("UPDATE day_close SET counted_cash = ?, closed_by = ?, closed_at = ? WHERE restaurant_id = ? AND business_day = ?",
                req.countedCash(), me.id(), Timestamp.from(Instant.now()), me.restaurantId(), day);
        }
        return day(me, day);
    }
}
