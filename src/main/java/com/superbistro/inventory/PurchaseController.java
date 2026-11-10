package com.superbistro.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;
import com.superbistro.daybook.CashBook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buying: a list of what to buy is saved as ORDERED ("to bring in"). When the goods arrive it is
 * RECEIVED with the actual quantities and costs: stock goes up and the payment is written to the day book.
 */
@RestController
@RequestMapping("/api/purchases")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class PurchaseController {

    public record Line(@NotNull UUID stockItemId, @NotNull @DecimalMin("0") BigDecimal qty, @DecimalMin("0") BigDecimal unitCost) {
    }

    public record NewPurchase(
        @Size(max = 80) String supplier,
        @Size(max = 300) String note,
        @NotEmpty(message = "add at least one item") @Size(max = 200) List<@Valid Line> lines,
        Boolean receiveNow,
        @Pattern(regexp = "CASH|UPI|CARD|BANK|CREDIT") String payment) {
    }

    public record Receive(
        @NotEmpty @Size(max = 200) List<@Valid Line> lines,
        @NotNull @Pattern(regexp = "CASH|UPI|CARD|BANK|CREDIT") String payment,
        @Size(max = 80) String supplier) {
    }

    public record LineDto(String stockItemId, String name, String unit, BigDecimal qty, BigDecimal unitCost, BigDecimal receivedQty) {
    }

    public record PurchaseDto(String id, String supplier, String note, String status, String payment, BigDecimal total,
                              long createdAt, Long receivedAt, String by, List<LineDto> lines) {
    }

    private final JdbcTemplate jdbc;
    private final StockController stock;
    private final CashBook cashBook;
    private final AppProperties props;

    public PurchaseController(JdbcTemplate jdbc, StockController stock, CashBook cashBook, AppProperties props) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.cashBook = cashBook;
        this.props = props;
    }

    /** Lists open orders ("to bring in") and recent received/cancelled ones. */
    @GetMapping
    public List<PurchaseDto> list(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) String status) {
        Instant since = Instant.now().minusSeconds(60L * 86400);
        List<Object> args = new ArrayList<>(List.of(me.restaurantId(), Timestamp.from(since)));
        String where = "p.restaurant_id = ? AND (p.status = 'ORDERED' OR p.created_at >= ?)";
        if (status != null && !status.isBlank()) {
            where += " AND p.status = ?";
            args.add(status);
        }
        Map<String, PurchaseDto> out = new LinkedHashMap<>();
        jdbc.query("SELECT p.*, u.name AS by_name FROM purchase p LEFT JOIN app_user u ON u.id = p.created_by WHERE " + where
                + " ORDER BY p.created_at DESC LIMIT 200", rs -> {
            Timestamp rec = rs.getTimestamp("received_at");
            out.put(rs.getString("id"), new PurchaseDto(rs.getString("id"), rs.getString("supplier"), rs.getString("note"),
                rs.getString("status"), rs.getString("payment"), rs.getBigDecimal("total"), rs.getTimestamp("created_at").getTime(),
                rec == null ? null : rec.getTime(), rs.getString("by_name"), new ArrayList<>()));
        }, args.toArray());
        if (out.isEmpty()) return List.of();
        String in = String.join(",", java.util.Collections.nCopies(out.size(), "?"));
        jdbc.query("SELECT l.*, s.name, s.unit FROM purchase_line l JOIN stock_item s ON s.id = l.stock_item_id WHERE l.purchase_id IN (" + in + ") ORDER BY s.name",
            rs -> {
                BigDecimal rq = rs.getBigDecimal("received_qty");
                out.get(rs.getString("purchase_id")).lines().add(new LineDto(rs.getString("stock_item_id"), rs.getString("name"),
                    rs.getString("unit"), StockController.qty(rs.getBigDecimal("qty")), rs.getBigDecimal("unit_cost"),
                    StockController.qty(rq)));
            }, out.keySet().stream().map(UUID::fromString).toArray());
        return new ArrayList<>(out.values());
    }

    @PostMapping
    @Transactional
    public PurchaseDto create(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody NewPurchase req) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase (id, restaurant_id, supplier, note, status, created_by, created_at) VALUES (?, ?, ?, ?, 'ORDERED', ?, ?)",
            id, me.restaurantId(), clean(req.supplier()), clean(req.note()), me.id(), Timestamp.from(Instant.now()));
        for (Line l : req.lines()) {
            requireItem(me, l.stockItemId());
            if (l.qty().signum() <= 0) throw ApiException.badRequest("Each item needs a quantity above 0");
            jdbc.update("INSERT INTO purchase_line (id, purchase_id, stock_item_id, qty, unit_cost) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, l.stockItemId(), l.qty(), l.unitCost());
        }
        if (Boolean.TRUE.equals(req.receiveNow())) {
            return receive(me, id, new Receive(req.lines(), req.payment() == null ? "CASH" : req.payment(), req.supplier()));
        }
        return one(me, id);
    }

    @PostMapping("/{id}/receive")
    @Transactional
    public PurchaseDto receive(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody Receive req) {
        List<String> st = jdbc.query("SELECT status FROM purchase WHERE id = ? AND restaurant_id = ? FOR UPDATE",
            (rs, i) -> rs.getString(1), id, me.restaurantId());
        if (st.isEmpty()) throw ApiException.notFound("Purchase");
        if (!"ORDERED".equals(st.get(0))) throw ApiException.badRequest("This purchase is already " + st.get(0).toLowerCase());

        BigDecimal total = BigDecimal.ZERO;
        for (Line l : req.lines()) {
            requireItem(me, l.stockItemId());
            BigDecimal qty = l.qty();
            int n = jdbc.update("UPDATE purchase_line SET received_qty = ?, unit_cost = COALESCE(?, unit_cost) WHERE purchase_id = ? AND stock_item_id = ?",
                qty, l.unitCost(), id, l.stockItemId());
            if (n == 0) { // brought something that wasn't on the list
                jdbc.update("INSERT INTO purchase_line (id, purchase_id, stock_item_id, qty, unit_cost, received_qty) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), id, l.stockItemId(), qty, l.unitCost(), qty);
            }
            if (qty.signum() > 0) {
                stock.apply(me.restaurantId(), l.stockItemId(), "IN", qty, null, l.unitCost(), "Received", id, me.id());
                if (l.unitCost() != null) total = total.add(qty.multiply(l.unitCost()));
            }
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        String supplier = clean(req.supplier());
        jdbc.update("UPDATE purchase SET status = 'RECEIVED', payment = ?, total = ?, received_at = ?"
                + (supplier.isEmpty() ? "" : ", supplier = ?") + " WHERE id = ?",
            supplier.isEmpty() ? new Object[] {req.payment(), total, Timestamp.from(Instant.now()), id}
                : new Object[] {req.payment(), total, Timestamp.from(Instant.now()), supplier, id});
        if (total.signum() > 0) {
            String who = supplier.isEmpty() ? jdbc.queryForObject("SELECT supplier FROM purchase WHERE id = ?", String.class, id) : supplier;
            cashBook.add(me.restaurantId(), LocalDate.now(props.zone()), "OUT", req.payment(), "Purchase", total,
                who == null || who.isBlank() ? "Store purchase" : who, id, me.id());
        }
        return one(me, id);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public PurchaseDto cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        int n = jdbc.update("UPDATE purchase SET status = 'CANCELLED' WHERE id = ? AND restaurant_id = ? AND status = 'ORDERED'", id, me.restaurantId());
        if (n == 0) throw ApiException.badRequest("Only purchases waiting to be brought in can be cancelled");
        return one(me, id);
    }

    private PurchaseDto one(CurrentUser me, UUID id) {
        return list(me, null).stream().filter(p -> p.id().equals(id.toString())).findFirst()
            .orElseThrow(() -> ApiException.notFound("Purchase"));
    }

    private void requireItem(CurrentUser me, UUID itemId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stock_item WHERE id = ? AND restaurant_id = ?", Integer.class, itemId, me.restaurantId());
        if (n == null || n == 0) throw ApiException.notFound("Store item");
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
