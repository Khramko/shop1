package com.example.shop.warehouse.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {

    public enum Status { RESERVED, RELEASED }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RESERVED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Reservation() {}
    public Reservation(Long orderId, String sku, int qty) {
        this.orderId = orderId; this.sku = sku; this.qty = qty;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
