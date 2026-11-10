package com.superbistro.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Store room: what you have, what is running low, and every change to stock levels. */
@RestController
@RequestMapping("/api/stock")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class StockController {

    public record StockItemDto(String id, String name, String unit, String category, BigDecimal currentQty,
                               BigDecimal minQty, BigDecimal reorderQty, BigDecimal lastCost, String status,
                               BigDecimal suggestQty, BigDecimal onOrderQty) {
    }

    public record StockItemRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 12) String unit,
        @Size(max = 40) String category,
        @DecimalMin("0") BigDecimal currentQty,
        @DecimalMin("0") BigDecimal minQty,
        @DecimalMin("0") BigDecimal reorderQty,
        @DecimalMin("0") BigDecimal lastCost) {
    }

    /** USE = used in the kitchen, WASTE = spoiled/thrown, COUNT = physical count (sets the level), IN = added. */
    public record MoveRequest(
        @NotNull @Pattern(regexp = "USE|WASTE|COUNT|IN") String type,
        @NotNull @DecimalMin("0") BigDecimal qty,
        @DecimalMin("0") BigDecimal unitCost,
        @Size(max = 200) String note) {
    }

    public record MoveDto(String type, BigDecimal qtyChange, BigDecimal qtyAfter, BigDecimal unitCost, String note,
                          long createdAt, String by) {
    }

    public record ImportRequest(@NotNull @Size(max = 1000) List<@Valid StockItemRequest> items) {
    }

    private final JdbcTemplate jdbc;

    public StockController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<StockItemDto> list(@AuthenticationPrincipal CurrentUser me) {
        Map<String, BigDecimal> onOrder = new HashMap<>();
        jdbc.query("SELECT l.stock_item_id, SUM(l.qty) FROM purchase_line l JOIN purchase p ON p.id = l.purchase_id"
                + " WHERE p.restaurant_id = ? AND p.status = 'ORDERED' GROUP BY l.stock_item_id",
            rs -> { onOrder.put(rs.getString(1), rs.getBigDecimal(2)); }, me.restaurantId());
        return jdbc.query("SELECT * FROM stock_item WHERE restaurant_id = ? AND active = TRUE ORDER BY category, name",
            (rs, i) -> {
                BigDecimal cur = rs.getBigDecimal("current_qty"), min = rs.getBigDecimal("min_qty"), re = rs.getBigDecimal("reorder_qty");
                String status = cur.signum() <= 0 ? "OUT" : (min.signum() > 0 && cur.compareTo(min) < 0 ? "LOW" : "OK");
                // Suggested buy: the usual order size, or enough to get back to twice the minimum.
                BigDecimal suggest = BigDecimal.ZERO;
                if (!"OK".equals(status) || (min.signum() > 0 && cur.compareTo(min) <= 0)) {
                    suggest = re.signum() > 0 ? re : min.multiply(BigDecimal.valueOf(2)).subtract(cur).max(BigDecimal.ZERO);
                }
                return new StockItemDto(rs.getString("id"), rs.getString("name"), rs.getString("unit"), rs.getString("category"),
                    qty(cur), qty(min), qty(re), rs.getBigDecimal("last_cost"),
                    status, qty(suggest), qty(onOrder.getOrDefault(rs.getString("id"), BigDecimal.ZERO)));
            }, me.restaurantId());
    }

    @PostMapping
    @Transactional
    public List<StockItemDto> create(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody StockItemRequest r) {
        if (findByName(me.restaurantId(), r.name()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "\"" + r.name().trim() + "\" is already in the store list");
        }
        insert(me, r);
        return list(me);
    }

    @PutMapping("/{id}")
    @Transactional
    public List<StockItemDto> update(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody StockItemRequest r) {
        int n = jdbc.update("UPDATE stock_item SET name = ?, unit = ?, category = ?, min_qty = ?, reorder_qty = ?, last_cost = ?"
                + " WHERE id = ? AND restaurant_id = ?",
            r.name().trim(), r.unit().trim(), category(r.category()), nz(r.minQty()), nz(r.reorderQty()), r.lastCost(), id, me.restaurantId());
        if (n == 0) throw ApiException.notFound("Store item");
        return list(me);
    }

    /** Hides the item; its history stays. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        jdbc.update("UPDATE stock_item SET active = FALSE WHERE id = ? AND restaurant_id = ?", id, me.restaurantId());
    }

    @PostMapping("/{id}/move")
    @Transactional
    public List<StockItemDto> move(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody MoveRequest m) {
        BigDecimal qty = m.qty().setScale(3, RoundingMode.HALF_UP);
        if (!"COUNT".equals(m.type()) && qty.signum() == 0) throw ApiException.badRequest("Enter a quantity above 0");
        BigDecimal change = switch (m.type()) {
            case "IN" -> qty;
            case "COUNT" -> null; // worked out from the current level below
            default -> qty.negate();
        };
        apply(me.restaurantId(), id, m.type(), change, "COUNT".equals(m.type()) ? qty : null, m.unitCost(), m.note(), null, me.id());
        return list(me);
    }

    @GetMapping("/{id}/moves")
    public List<MoveDto> moves(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        return jdbc.query("SELECT m.*, u.name AS by_name FROM stock_move m LEFT JOIN app_user u ON u.id = m.created_by"
                + " WHERE m.stock_item_id = ? AND m.restaurant_id = ? ORDER BY m.created_at DESC LIMIT 60",
            (rs, i) -> new MoveDto(rs.getString("move_type"), qty(rs.getBigDecimal("qty_change")),
                qty(rs.getBigDecimal("qty_after")), rs.getBigDecimal("unit_cost"), rs.getString("note"),
                rs.getTimestamp("created_at").getTime(), rs.getString("by_name")),
            id, me.restaurantId());
    }

    /** Bulk add from a spreadsheet; existing names get their unit, levels and cost updated. */
    @PostMapping("/import")
    @Transactional
    public Map<String, Object> importItems(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody ImportRequest req) {
        int added = 0, updated = 0;
        for (StockItemRequest r : req.items()) {
            String existing = findByName(me.restaurantId(), r.name());
            if (existing == null) {
                insert(me, r);
                added++;
            } else {
                jdbc.update("UPDATE stock_item SET unit = ?, category = ?, min_qty = ?, reorder_qty = ?, last_cost = COALESCE(?, last_cost), active = TRUE WHERE id = ?",
                    r.unit().trim(), category(r.category()), nz(r.minQty()), nz(r.reorderQty()), r.lastCost(), UUID.fromString(existing));
                if (r.currentQty() != null) {
                    apply(me.restaurantId(), UUID.fromString(existing), "COUNT", null, r.currentQty(), null, "Imported", null, me.id());
                }
                updated++;
            }
        }
        return Map.of("added", added, "updated", updated, "items", list(me));
    }

    /**
     * Changes a stock level and records it. For COUNT pass the counted level as setTo; otherwise
     * pass the signed change. Locks the row so two people can't overwrite each other's change.
     */
    public void apply(UUID restaurantId, UUID itemId, String type, BigDecimal change, BigDecimal setTo,
                      BigDecimal unitCost, String note, UUID purchaseId, UUID userId) {
        List<BigDecimal> cur = jdbc.query("SELECT current_qty FROM stock_item WHERE id = ? AND restaurant_id = ? FOR UPDATE",
            (rs, i) -> rs.getBigDecimal(1), itemId, restaurantId);
        if (cur.isEmpty()) throw ApiException.notFound("Store item");
        BigDecimal before = cur.get(0);
        BigDecimal delta = setTo != null ? setTo.subtract(before) : change;
        BigDecimal after = before.add(delta);
        if (after.signum() < 0) {
            throw ApiException.badRequest("Only " + qty(before).toPlainString() + " left. Do a stock count if the level is wrong.");
        }
        jdbc.update("UPDATE stock_item SET current_qty = ?" + (unitCost != null ? ", last_cost = ?" : "") + " WHERE id = ?",
            unitCost != null ? new Object[] {after, unitCost, itemId} : new Object[] {after, itemId});
        jdbc.update("INSERT INTO stock_move (id, restaurant_id, stock_item_id, move_type, qty_change, qty_after, unit_cost, note, purchase_id, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), restaurantId, itemId, type, delta, after, unitCost, note == null ? "" : note.trim(), purchaseId, userId,
            Timestamp.from(Instant.now()));
    }

    private void insert(CurrentUser me, StockItemRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_item (id, restaurant_id, name, unit, category, current_qty, min_qty, reorder_qty, last_cost, active, created_at)"
                + " VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, TRUE, ?)",
            id, me.restaurantId(), r.name().trim(), r.unit().trim(), category(r.category()), nz(r.minQty()), nz(r.reorderQty()),
            r.lastCost(), Timestamp.from(Instant.now()));
        if (r.currentQty() != null && r.currentQty().signum() > 0) {
            apply(me.restaurantId(), id, "COUNT", null, r.currentQty(), null, "Opening stock", null, me.id());
        }
    }

    private String findByName(UUID restaurantId, String name) {
        List<String> ids = jdbc.query("SELECT id FROM stock_item WHERE restaurant_id = ? AND LOWER(name) = ?",
            (rs, i) -> rs.getString(1), restaurantId, name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 20.000 -> 20, 2.500 -> 2.5 (never 2E+1). */
    public static BigDecimal qty(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String category(String c) {
        return c == null || c.isBlank() ? "Other" : c.trim();
    }
}
