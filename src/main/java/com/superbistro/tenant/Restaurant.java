package com.superbistro.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tenant: one restaurant with its own menu, staff and orders. */
@Entity
@Table(name = "restaurant")
public class Restaurant {
    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "gst_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 200)
    private String address = "";

    @Column(nullable = false, length = 40)
    private String phone = "";

    @Column(nullable = false, length = 20)
    private String gstin = "";

    @Column(name = "bill_footer", nullable = false, length = 200)
    private String billFooter = "";

    protected Restaurant() {
    }

    public Restaurant(String name, BigDecimal gstPct) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.gstPct = gstPct;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getGstPct() { return gstPct; }
    public void setGstPct(BigDecimal gstPct) { this.gstPct = gstPct; }
    public Instant getCreatedAt() { return createdAt; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getGstin() { return gstin; }
    public String getBillFooter() { return billFooter; }

    public void setBillDetails(String address, String phone, String gstin, String billFooter) {
        this.address = address;
        this.phone = phone;
        this.gstin = gstin;
        this.billFooter = billFooter;
    }
}
