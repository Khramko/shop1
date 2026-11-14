package com.example.shop.delivery.model;

import jakarta.persistence.*;

@Entity
@Table(name = "courier_slots")
public class CourierSlot {

    public enum Status { FREE, RESERVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "courier_id", nullable = false)
    private Long courierId;

    @Column(name = "slot", nullable = false, length = 32)
    private String slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.FREE;

    public CourierSlot() {}
    public CourierSlot(Long courierId, String slot) { this.courierId = courierId; this.slot = slot; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCourierId() { return courierId; }
    public void setCourierId(Long courierId) { this.courierId = courierId; }
    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
