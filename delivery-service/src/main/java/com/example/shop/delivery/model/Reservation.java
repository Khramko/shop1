package com.example.shop.delivery.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations")
public class Reservation {

    public enum Status { RESERVED, RELEASED }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "courier_id", nullable = false)
    private Long courierId;

    @Column(name = "slot", nullable = false, length = 32)
    private String slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RESERVED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Reservation() {}
    public Reservation(Long orderId, Long courierId, String slot) {
        this.orderId = orderId; this.courierId = courierId; this.slot = slot;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getCourierId() { return courierId; }
    public void setCourierId(Long courierId) { this.courierId = courierId; }
    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
