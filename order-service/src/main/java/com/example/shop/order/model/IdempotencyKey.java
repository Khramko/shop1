package com.example.shop.order.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency_user_key",
                columnNames = {"user_id", "idempotency_key"}))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    // nullable: placeholder создаётся до заказа, order_id заполняется позже
    @Column(name = "order_id")
    private Long orderId;

    // nullable: заполняется вместе с order_id в updateResult
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    // nullable: Integer, а не int — placeholder имеет null, awaitFilled это проверяет
    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyKey() {}

    /** Конструктор placeholder: создаётся до заказа, order_id/body/status = null. */
    public IdempotencyKey(Long userId, String key) {
        this.userId = userId;
        this.idempotencyKey = key;
    }

    /** Полный конструктор: используется, если пишете результат сразу (не placeholder-схема). */
    public IdempotencyKey(Long userId, String key, Long orderId, String body, int status) {
        this.userId = userId;
        this.idempotencyKey = key;
        this.orderId = orderId;
        this.responseBody = body;
        this.httpStatus = status;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getOrderId() { return orderId; }
    public String getResponseBody() { return responseBody; }
    public Integer getHttpStatus() { return httpStatus; }
    public Instant getCreatedAt() { return createdAt; }
}