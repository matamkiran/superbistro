package com.superbistro.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.superbistro.order.OrderEnums.Payment;
import com.superbistro.order.OrderEnums.Source;
import com.superbistro.order.OrderEnums.Status;
import com.superbistro.order.OrderEnums.Type;

@Entity
@Table(name = "customer_order")
public class CustomerOrder {
    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(name = "business_day", nullable = false)
    private LocalDate businessDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 16)
    private Type type;

    @Column(name = "table_no", nullable = false, length = 20)
    private String tableNo = "";

    @Column(nullable = false, length = 80)
    private String customer = "";

    @Column(nullable = false, length = 20)
    private String phone = "";

    @Column(name = "platform_id", nullable = false, length = 40)
    private String platformId = "";

    @Column(nullable = false, length = 300)
    private String note = "";

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discount;

    @Column(name = "gst_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPct;

    @Column(name = "gst_amt", nullable = false, precision = 10, scale = 2)
    private BigDecimal gstAmt;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Latest kitchen round: 1 for a new order, +1 each time dishes are added to the running bill. */
    @Column(name = "kot_round", nullable = false)
    private int kotRound = 1;

    @Column(nullable = false, length = 300)
    private String address = "";

    @Column(name = "cancel_reason", nullable = false, length = 200)
    private String cancelReason = "";

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<OrderItem> items = new ArrayList<>();

    protected CustomerOrder() {
    }

    public CustomerOrder(UUID restaurantId, int orderNo, LocalDate businessDay, Source source, Type type, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.restaurantId = restaurantId;
        this.orderNo = orderNo;
        this.businessDay = businessDay;
        this.source = source;
        this.type = type;
        this.status = Status.NEW;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addItem(UUID menuItemId, String name, int qty, BigDecimal price, BigDecimal menuPrice,
                        BigDecimal lineDiscount, String note) {
        items.add(new OrderItem(this, items.size() + 1, menuItemId, name, qty, price, menuPrice, lineDiscount, note, kotRound));
        this.updatedAt = Instant.now();
    }

    /** Starts the next kitchen round: dishes added from now on go on a new KOT. */
    public void nextRound() {
        this.kotRound++;
    }

    public void cancel(UUID by, String reason) {
        this.status = Status.CANCELLED;
        this.cancelledBy = by;
        this.cancelledAt = Instant.now();
        this.cancelReason = reason;
        this.updatedAt = this.cancelledAt;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void setPayment(Payment payment) {
        this.payment = payment;
        this.updatedAt = Instant.now();
    }

    public void setDetails(String tableNo, String customer, String phone, String platformId, String note) {
        this.tableNo = tableNo;
        this.customer = customer;
        this.phone = phone;
        this.platformId = platformId;
        this.note = note;
    }

    public void setAmounts(BigDecimal subtotal, BigDecimal discount, BigDecimal gstPct, BigDecimal gstAmt, BigDecimal total) {
        this.subtotal = subtotal;
        this.discount = discount;
        this.gstPct = gstPct;
        this.gstAmt = gstAmt;
        this.total = total;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public int getOrderNo() { return orderNo; }
    public LocalDate getBusinessDay() { return businessDay; }
    public Source getSource() { return source; }
    public Type getType() { return type; }
    public String getTableNo() { return tableNo; }
    public String getCustomer() { return customer; }
    public String getPhone() { return phone; }
    public String getPlatformId() { return platformId; }
    public String getNote() { return note; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getGstPct() { return gstPct; }
    public BigDecimal getGstAmt() { return gstAmt; }
    public BigDecimal getTotal() { return total; }
    public Payment getPayment() { return payment; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return items; }
    public int getKotRound() { return kotRound; }
    public String getAddress() { return address; }
    public String getCancelReason() { return cancelReason; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
}
