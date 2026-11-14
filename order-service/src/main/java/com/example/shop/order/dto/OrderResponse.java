package com.example.shop.order.dto;

import com.example.shop.order.model.Order;
import java.math.BigDecimal;
import java.time.Instant;

public class OrderResponse {
    private Long orderId;
    private Long userId;
    private BigDecimal price;
    private String status;
    private Instant createdAt;

    public OrderResponse() {}
    public static OrderResponse from(Order o) {
        OrderResponse r = new OrderResponse();
        r.orderId = o.getId();
        r.userId = o.getUserId();
        r.price = o.getPrice();
        r.status = o.getStatus().name();
        r.createdAt = o.getCreatedAt();
        return r;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
