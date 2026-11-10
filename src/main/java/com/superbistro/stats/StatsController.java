package com.superbistro.stats;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;
import com.superbistro.order.CustomerOrder;
import com.superbistro.order.OrderEnums.Source;
import com.superbistro.order.OrderEnums.Status;
import com.superbistro.order.OrderItem;
import com.superbistro.order.OrderRepository;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Top-selling dishes, sales by channel and busy hours. Cancelled orders are excluded. */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    public record SourceTotals(long orders, BigDecimal revenue) {
    }

    public record TopItem(String name, long qty, BigDecimal revenue, long orders, Map<Source, Long> qtyBySource) {
    }

    public record Stats(long orders, BigDecimal revenue, Map<Source, SourceTotals> bySource, List<TopItem> top, int[] hours) {
    }

    private static final class Acc {
        String name;
        long qty;
        BigDecimal revenue = BigDecimal.ZERO;
        long orders;
        final Map<Source, Long> bySource = new EnumMap<>(Source.class);
    }

    private final OrderRepository orders;
    private final AppProperties props;

    public StatsController(OrderRepository orders, AppProperties props) {
        this.orders = orders;
        this.props = props;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Stats stats(@AuthenticationPrincipal CurrentUser me,
                       @RequestParam(required = false) Long from,
                       @RequestParam(required = false) Long to,
                       @RequestParam(required = false) Source source,
                       @RequestParam(defaultValue = "25") int limit) {
        Instant start = from != null ? Instant.ofEpochMilli(from) : LocalDate.now(props.zone()).atStartOfDay(props.zone()).toInstant();
        Instant end = to != null ? Instant.ofEpochMilli(to) : Instant.now().plus(Duration.ofDays(1));
        if (Duration.between(start, end).compareTo(Duration.ofDays(400)) > 0) {
            throw ApiException.badRequest("Pick a period of about a year or less");
        }

        long count = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        Map<Source, SourceTotals> bySource = new EnumMap<>(Source.class);
        for (Source s : Source.values()) {
            bySource.put(s, new SourceTotals(0, BigDecimal.ZERO));
        }
        Map<String, Acc> items = new LinkedHashMap<>();
        int[] hours = new int[24];

        for (CustomerOrder o : orders.findWithItems(me.restaurantId(), start, end)) {
            if (o.getStatus() == Status.CANCELLED || (source != null && o.getSource() != source)) {
                continue;
            }
            count++;
            revenue = revenue.add(o.getTotal());
            SourceTotals st = bySource.get(o.getSource());
            bySource.put(o.getSource(), new SourceTotals(st.orders() + 1, st.revenue().add(o.getTotal())));
            hours[o.getCreatedAt().atZone(props.zone()).getHour()]++;

            for (OrderItem i : o.getItems()) {
                // Group by dish id so a renamed dish keeps its history; fall back to name.
                String key = i.getMenuItemId() != null ? i.getMenuItemId().toString() : "name:" + i.getName();
                Acc a = items.computeIfAbsent(key, k -> new Acc());
                a.name = i.getName();
                a.qty += i.getQty();
                a.revenue = a.revenue.add(i.lineTotal());
                a.orders++;
                a.bySource.merge(o.getSource(), (long) i.getQty(), Long::sum);
            }
        }

        List<TopItem> top = new ArrayList<>();
        items.values().stream()
            .sorted(Comparator.comparingLong((Acc a) -> a.qty).reversed().thenComparing(a -> a.revenue, Comparator.reverseOrder()))
            .limit(Math.max(1, Math.min(limit, 100)))
            .forEach(a -> {
                Map<Source, Long> m = new EnumMap<>(Source.class);
                for (Source s : Source.values()) {
                    m.put(s, a.bySource.getOrDefault(s, 0L));
                }
                top.add(new TopItem(a.name, a.qty, a.revenue, a.orders, m));
            });

        return new Stats(count, revenue, bySource, top, hours);
    }
}
