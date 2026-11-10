package com.superbistro.order;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One line on an order. Name and price are copied from the menu so old bills never change. */
@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "menu_item_id")
    private UUID menuItemId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** Menu price at the time; differs from price when the cashier changed it. */
    @Column(name = "menu_price", precision = 10, scale = 2)
    private BigDecimal menuPrice;

    /** Rupees taken off this line (already worked out from % or ₹). */
    @Column(name = "line_discount", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineDiscount = BigDecimal.ZERO;

    /** Instruction for the kitchen, e.g. "less spicy". */
    @Column(nullable = false, length = 120)
    private String note = "";

    protected OrderItem() {
    }

    OrderItem(CustomerOrder order, int lineNo, UUID menuItemId, String name, int qty, BigDecimal price,
              BigDecimal menuPrice, BigDecimal lineDiscount, String note) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.lineNo = lineNo;
        this.menuItemId = menuItemId;
        this.name = name;
        this.qty = qty;
        this.price = price;
        this.menuPrice = menuPrice;
        this.lineDiscount = lineDiscount;
        this.note = note;
    }

    /** qty x price minus the line discount. */
    public BigDecimal lineTotal() {
        return price.multiply(BigDecimal.valueOf(qty)).subtract(lineDiscount);
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public UUID getMenuItemId() { return menuItemId; }
    public String getName() { return name; }
    public int getQty() { return qty; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getMenuPrice() { return menuPrice == null ? price : menuPrice; }
    public BigDecimal getLineDiscount() { return lineDiscount; }
    public String getNote() { return note; }
}
