package com.example.shop.billing.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_orders")
public class ProcessedOrder {
    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public ProcessedOrder() {}
    public ProcessedOrder(Long orderId) { this.orderId = orderId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
