package com.example.shop.order.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "saga_states")
public class SagaState {

    public enum Step { STARTED, FUNDS_RESERVED, STOCK_RESERVED, COURIER_RESERVED, DONE, FAILED, COMPENSATING, COMPENSATION_FAILED }

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Step step = Step.STARTED;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public SagaState() {}
    public SagaState(Long orderId) { this.orderId = orderId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Step getStep() { return step; }
    public void setStep(Step step) { this.step = step; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
