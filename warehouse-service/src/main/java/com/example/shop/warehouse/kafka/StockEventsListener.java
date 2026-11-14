package com.example.shop.warehouse.kafka;

import com.example.shop.common.events.SagaEvents.*;
import com.example.shop.common.events.Topics;
import com.example.shop.warehouse.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class StockEventsListener {

    private static final Logger log = LoggerFactory.getLogger(StockEventsListener.class);
    private final WarehouseService service;
    private final KafkaTemplate<String, Object> kafka;

    public StockEventsListener(WarehouseService service, KafkaTemplate<String, Object> kafka) {
        this.service = service; this.kafka = kafka;
    }

    @KafkaListener(
        topics = Topics.STOCK_RESERVE_COMMAND, groupId = "warehouse-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReserveStockCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    public void onReserve(ReserveStockCommand cmd) {
        String reason = service.reserve(cmd.orderId(), cmd.sku(), cmd.qty());
        if (reason == null) {
            kafka.send(Topics.STOCK_RESERVED, cmd.orderId().toString(),
                new StockReservedEvent(cmd.orderId(), cmd.sku(), cmd.qty()));
        } else {
            log.warn("Reserve failed order={} reason={}", cmd.orderId(), reason);
            kafka.send(Topics.STOCK_RESERVE_FAILED, cmd.orderId().toString(),
                new StockReserveFailedEvent(cmd.orderId(), reason));
        }
    }

    @KafkaListener(
        topics = Topics.STOCK_RELEASE_COMMAND, groupId = "warehouse-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReleaseStockCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    public void onRelease(ReleaseStockCommand cmd) {
        try {
            service.release(cmd.orderId());
            kafka.send(Topics.STOCK_RELEASED, cmd.orderId().toString(),
                new StockReleasedEvent(cmd.orderId()));
        } catch (Exception ex) {
            log.error("Release failed order={}", cmd.orderId(), ex);
            kafka.send(Topics.STOCK_RELEASE_FAILED, cmd.orderId().toString(),
                new StockReleaseFailedEvent(cmd.orderId(), ex.getMessage()));
        }
    }
}
