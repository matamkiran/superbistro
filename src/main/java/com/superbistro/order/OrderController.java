package com.superbistro.order;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;
import com.superbistro.order.OrderDtos.NewOrder;
import com.superbistro.order.OrderDtos.OrderDto;
import com.superbistro.order.OrderDtos.AddItems;
import com.superbistro.order.OrderDtos.OrderPatch;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Duration MAX_RANGE = Duration.ofDays(93);

    private final OrderService service;
    private final AppProperties props;

    public OrderController(OrderService service, AppProperties props) {
        this.service = service;
        this.props = props;
    }

    /** Orders between two epoch-millisecond times. Default: yesterday and today. */
    @GetMapping
    public List<OrderDto> list(@AuthenticationPrincipal CurrentUser me,
                               @RequestParam(required = false) Long from,
                               @RequestParam(required = false) Long to) {
        Instant start = from != null ? Instant.ofEpochMilli(from)
            : LocalDate.now(props.zone()).minusDays(1).atStartOfDay(props.zone()).toInstant();
        Instant end = to != null ? Instant.ofEpochMilli(to) : Instant.now().plus(Duration.ofDays(1));
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("Pick a period of 93 days or less");
        }
        return service.list(me, start, end);
    }

    @PostMapping
    public OrderDto create(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody NewOrder req) {
        return service.create(me, req);
    }

    /** Running table bill: add dishes to an open order. */
    @PostMapping("/{id}/items")
    public OrderDto addItems(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody AddItems req) {
        return service.addItems(me, id, req.items());
    }

    @PatchMapping("/{id}")
    public OrderDto patch(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody OrderPatch req) {
        return service.patch(me, id, req);
    }
}
