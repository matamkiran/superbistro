package com.superbistro.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;
import com.superbistro.menu.MenuItem;
import com.superbistro.menu.MenuItemRepository;
import com.superbistro.order.OrderDtos.NewLine;
import com.superbistro.order.OrderDtos.NewOrder;
import com.superbistro.order.OrderDtos.OrderDto;
import com.superbistro.order.OrderDtos.OrderPatch;
import com.superbistro.order.OrderEnums.Payment;
import com.superbistro.order.OrderEnums.Source;
import com.superbistro.order.OrderEnums.Type;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OrderRepository orders;
    private final MenuItemRepository menu;
    private final RestaurantRepository restaurants;
    private final AppProperties props;

    public OrderService(OrderRepository orders, MenuItemRepository menu, RestaurantRepository restaurants, AppProperties props) {
        this.orders = orders;
        this.menu = menu;
        this.restaurants = restaurants;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public List<OrderDto> list(CurrentUser me, Instant from, Instant to) {
        return orders.findWithItems(me.restaurantId(), from, to).stream().map(OrderDto::of).toList();
    }

    @Transactional
    public OrderDto create(CurrentUser me, NewOrder req) {
        // Lock the restaurant row so concurrent tills get consecutive, unique order numbers.
        Restaurant r = restaurants.lockById(me.restaurantId()).orElseThrow(() -> ApiException.notFound("Restaurant"));

        // Dishes and menu prices always come from the server. A price typed at the till replaces the
        // menu price for this order only, and the menu price is kept next to it for reports.
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (NewLine l : req.items()) {
            ids.add(l.menuItemId());
        }
        Map<UUID, MenuItem> dishes = menu.findByRestaurantIdAndIdIn(r.getId(), ids).stream()
            .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        if (dishes.size() != ids.size()) {
            throw ApiException.badRequest("One of the dishes is no longer on the menu. Refresh and try again.");
        }

        Source source = req.source();
        Type type = source == Source.OFFLINE ? (req.type() == null ? Type.DINE_IN : req.type()) : Type.DELIVERY;
        Payment payment = source == Source.OFFLINE ? (req.payment() == null ? Payment.CASH : req.payment()) : Payment.PLATFORM;
        if (source == Source.OFFLINE && payment == Payment.PLATFORM) {
            throw ApiException.badRequest("Offline orders are paid by cash, UPI, card or later");
        }

        LocalDate day = LocalDate.now(props.zone());
        int no = orders.maxOrderNo(r.getId(), day) + 1;
        CustomerOrder o = new CustomerOrder(r.getId(), no, day, source, type, me.id());
        o.setPayment(payment);
        o.setDetails(
            type == Type.DINE_IN ? clean(req.table()) : "",
            clean(req.customer()), clean(req.phone()),
            source == Source.OFFLINE ? "" : clean(req.platformId()),
            clean(req.note()));

        BigDecimal subtotal = BigDecimal.ZERO;
        for (NewLine l : req.items()) {
            MenuItem d = dishes.get(l.menuItemId());
            BigDecimal price = money(l.price() != null ? l.price() : d.getPrice());
            BigDecimal gross = price.multiply(BigDecimal.valueOf(l.qty()));
            BigDecimal lineDiscount = BigDecimal.ZERO;
            if (l.discount() != null && l.discount().signum() > 0) {
                lineDiscount = "PCT".equals(l.discountType())
                    ? gross.multiply(l.discount().min(HUNDRED)).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    : l.discount();
                lineDiscount = money(lineDiscount.min(gross));
            }
            o.addItem(d.getId(), d.getName(), l.qty(), price, d.getPrice(), lineDiscount, clean(l.note()));
            subtotal = subtotal.add(gross.subtract(lineDiscount));
        }
        BigDecimal discount = req.discount() == null ? BigDecimal.ZERO : req.discount().min(subtotal);
        BigDecimal gstPct = r.getGstPct();
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal gstAmt = taxable.multiply(gstPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        o.setAmounts(money(subtotal), money(discount), gstPct, gstAmt, money(taxable.add(gstAmt)));

        return OrderDto.of(orders.save(o));
    }

    @Transactional
    public OrderDto patch(CurrentUser me, UUID id, OrderPatch req) {
        CustomerOrder o = orders.findOne(id, me.restaurantId()).orElseThrow(() -> ApiException.notFound("Order"));
        if (req.status() == null && req.payment() == null) {
            throw ApiException.badRequest("Nothing to change");
        }
        if (req.status() != null) {
            o.setStatus(req.status());
        }
        if (req.payment() != null) {
            if (o.getSource() == Source.OFFLINE && req.payment() == Payment.PLATFORM) {
                throw ApiException.badRequest("Offline orders are paid by cash, UPI, card or later");
            }
            o.setPayment(req.payment());
        }
        return OrderDto.of(o);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
