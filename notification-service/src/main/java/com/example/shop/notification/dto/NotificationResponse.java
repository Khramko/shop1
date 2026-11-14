package com.example.shop.notification.dto;

import com.example.shop.notification.model.Notification;
import java.time.Instant;

public class NotificationResponse {
    private Long id;
    private Long userId;
    private Long orderId;
    private String type;
    private String subject;
    private String body;
    private Instant createdAt;

    public static NotificationResponse from(Notification n) {
        NotificationResponse r = new NotificationResponse();
        r.id = n.getId();
        r.userId = n.getUserId();
        r.orderId = n.getOrderId();
        r.type = n.getType().name();
        r.subject = n.getSubject();
        r.body = n.getBody();
        r.createdAt = n.getCreatedAt();
        return r;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getOrderId() { return orderId; }
    public String getType() { return type; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
