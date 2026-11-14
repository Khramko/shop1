package com.example.shop.order.dto;

import com.example.shop.order.model.Order;
import java.math.BigDecimal;
import java.time.Instant;

public class OrderResponse {
    private Long orderId;
    private Long userId;
    private BigDecimal price;
    private String sku;
    private Integer qty;
    private String deliverySlot;
    private String status;
    private Instant createdAt;

    public static OrderResponse from(Order o) {
        OrderResponse r = new OrderResponse();
        r.orderId = o.getId(); r.userId = o.getUserId();
        r.price = o.getPrice(); r.sku = o.getSku();
        r.qty = o.getQty(); r.deliverySlot = o.getDeliverySlot();
        r.status = o.getStatus().name(); r.createdAt = o.getCreatedAt();
        return r;
    }

    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public BigDecimal getPrice() { return price; }
    public String getSku() { return sku; }
    public Integer getQty() { return qty; }
    public String getDeliverySlot() { return deliverySlot; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
