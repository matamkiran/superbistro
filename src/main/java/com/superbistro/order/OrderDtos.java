package com.superbistro.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.superbistro.order.OrderEnums.Payment;
import com.superbistro.order.OrderEnums.Source;
import com.superbistro.order.OrderEnums.Status;
import com.superbistro.order.OrderEnums.Type;

public final class OrderDtos {
    private OrderDtos() {
    }

    /**
     * One dish on a new order. price: optional, replaces the menu price for this order only.
     * discount + discountType ("PCT" or "AMT"): optional discount on this line. note: for the kitchen.
     */
    public record NewLine(
        @NotNull UUID menuItemId,
        @NotNull @Min(1) @Max(999) Integer qty,
        @DecimalMin("0") BigDecimal price,
        @DecimalMin("0") BigDecimal discount,
        @jakarta.validation.constraints.Pattern(regexp = "PCT|AMT") String discountType,
        @Size(max = 120) String note) {
    }

    public record NewOrder(
        @NotNull Source source,
        Type type,
        Payment payment,
        @Size(max = 20) String table,
        @Size(max = 80) String customer,
        @jakarta.validation.constraints.Pattern(regexp = "|\\+?[0-9 -]{6,16}", message = "should be a mobile number like 9876543210") String phone,
        @Size(max = 40) String platformId,
        @Size(max = 300) String note,
        @Size(max = 300) String address,
        @DecimalMin("0") BigDecimal discount,
        @NotEmpty(message = "add at least one dish") @Size(max = 100) List<@Valid NewLine> items) {
    }

    /** reason: required when cancelling. */
    public record OrderPatch(Status status, Payment payment, @Size(max = 200) String reason) {
    }

    /** More dishes for an open order (running table bill). */
    public record AddItems(@NotEmpty(message = "add at least one dish") @Size(max = 100) List<@Valid NewLine> items) {
    }

    public record LineDto(String menuItemId, String name, int qty, BigDecimal price, BigDecimal menuPrice,
                          BigDecimal lineDiscount, BigDecimal lineTotal, String note, int round) {
        static LineDto of(OrderItem i) {
            return new LineDto(i.getMenuItemId() == null ? null : i.getMenuItemId().toString(), i.getName(), i.getQty(),
                i.getPrice(), i.getMenuPrice(), i.getLineDiscount(), i.lineTotal(), i.getNote(), i.getKotRound());
        }
    }

    public record OrderDto(
        String id, int no, String day, Source source, Type type, String table, String customer, String phone,
        String platformId, String note, BigDecimal subtotal, BigDecimal discount, BigDecimal gstPct, BigDecimal gstAmt,
        BigDecimal total, Payment payment, Status status, long createdAt, long updatedAt, List<LineDto> items,
        String address, int round, String cancelReason, Long cancelledAt) {

        static OrderDto of(CustomerOrder o) {
            return new OrderDto(
                o.getId().toString(), o.getOrderNo(), o.getBusinessDay().toString(), o.getSource(), o.getType(),
                o.getTableNo(), o.getCustomer(), o.getPhone(), o.getPlatformId(), o.getNote(), o.getSubtotal(),
                o.getDiscount(), o.getGstPct(), o.getGstAmt(), o.getTotal(), o.getPayment(), o.getStatus(),
                o.getCreatedAt().toEpochMilli(), o.getUpdatedAt().toEpochMilli(),
                o.getItems().stream().map(LineDto::of).toList(),
                o.getAddress(), o.getKotRound(), o.getCancelReason(),
                o.getCancelledAt() == null ? null : o.getCancelledAt().toEpochMilli());
        }
    }
}
