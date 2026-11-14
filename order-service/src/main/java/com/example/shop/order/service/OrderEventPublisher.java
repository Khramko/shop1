package com.example.shop.order.service;

import com.example.shop.common.events.*;
import com.example.shop.order.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    private final KafkaTemplate<String, Object> kafka;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    public void publishOrderCreated(Order o) {
        send(Topics.ORDER_CREATED, o.getId().toString(),
             new OrderCreatedEvent(o.getId(), o.getUserId(), o.getPrice()));
    }

    public void publishOrderPaid(Order o) {
        send(Topics.ORDER_PAID, o.getId().toString(),
             new OrderPaidEvent(o.getId(), o.getUserId(), o.getPrice()));
    }

    public void publishOrderFailed(Order o, String reason) {
        send(Topics.ORDER_FAILED, o.getId().toString(),
             new OrderFailedEvent(o.getId(), o.getUserId(), o.getPrice(), reason));
    }

    private void send(String topic, String key, Object payload) {
        kafka.send(topic, key, payload).whenComplete((res, ex) -> {
            if (ex != null) log.error("Kafka publish failed topic={}", topic, ex);
            else            log.info("Published {} key={} offset={}",
                                     topic, key, res.getRecordMetadata().offset());
        });
    }
}
