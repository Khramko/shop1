package com.example.shop.delivery.kafka;

import com.example.shop.common.events.SagaEvents.*;
import com.example.shop.common.events.Topics;
import com.example.shop.delivery.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CourierEventsListener {

    private static final Logger log = LoggerFactory.getLogger(CourierEventsListener.class);
    private final DeliveryService service;
    private final KafkaTemplate<String, Object> kafka;

    public CourierEventsListener(DeliveryService service, KafkaTemplate<String, Object> kafka) {
        this.service = service; this.kafka = kafka;
    }

    @KafkaListener(
        topics = Topics.COURIER_RESERVE_COMMAND, groupId = "delivery-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReserveCourierCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    public void onReserve(ReserveCourierCommand cmd) {
        try {
            Long courierId = service.reserve(cmd.orderId(), cmd.slot());
            kafka.send(Topics.COURIER_RESERVED, cmd.orderId().toString(),
                new CourierReservedEvent(cmd.orderId(), courierId));
        } catch (Exception ex) {
            log.warn("Reserve courier failed order={} reason={}", cmd.orderId(), ex.getMessage());
            kafka.send(Topics.COURIER_RESERVE_FAILED, cmd.orderId().toString(),
                new CourierReserveFailedEvent(cmd.orderId(), ex.getMessage()));
        }
    }

    @KafkaListener(
        topics = Topics.COURIER_RELEASE_COMMAND, groupId = "delivery-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReleaseCourierCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    public void onRelease(ReleaseCourierCommand cmd) {
        try {
            service.release(cmd.orderId());
            kafka.send(Topics.COURIER_RELEASED, cmd.orderId().toString(),
                new CourierReleasedEvent(cmd.orderId()));
        } catch (Exception ex) {
            log.error("Release courier failed order={}", cmd.orderId(), ex);
            kafka.send(Topics.COURIER_RELEASE_FAILED, cmd.orderId().toString(),
                new CourierReleaseFailedEvent(cmd.orderId(), ex.getMessage()));
        }
    }
}
