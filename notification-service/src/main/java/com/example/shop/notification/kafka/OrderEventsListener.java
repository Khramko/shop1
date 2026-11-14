package com.example.shop.notification.kafka;

import com.example.shop.common.events.*;
import com.example.shop.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private final NotificationService notificationService;

    public OrderEventsListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = Topics.ORDER_PAID,
            groupId = "notification-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.OrderPaidEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    public void onOrderPaid(OrderPaidEvent e) {
        log.info("Received ORDER_PAID orderId={} userId={}", e.orderId(), e.userId());
        notificationService.saveHappy(e.userId(), e.orderId(), e.price());
    }

    @KafkaListener(
            topics = Topics.ORDER_FAILED,
            groupId = "notification-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.OrderFailedEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    public void onOrderFailed(OrderFailedEvent e) {
        log.info("Received ORDER_FAILED orderId={} userId={} reason={}",
                 e.orderId(), e.userId(), e.reason());
        notificationService.saveAngry(e.userId(), e.orderId(), e.price(), e.reason());
    }
}
