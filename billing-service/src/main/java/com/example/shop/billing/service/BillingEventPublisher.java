package com.example.shop.billing.service;

import com.example.shop.common.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class BillingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BillingEventPublisher.class);
    private final KafkaTemplate<String, Object> kafka;

    public BillingEventPublisher(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    public void publishFundsWithdrawn(FundsWithdrawnEvent e) {
        send(Topics.FUNDS_WITHDRAWN, e.userId().toString(), e);
    }

    public void publishFundsWithdrawFailed(FundsWithdrawFailedEvent e) {
        send(Topics.FUNDS_WITHDRAW_FAILED, e.userId().toString(), e);
    }

    private void send(String topic, String key, Object payload) {
        kafka.send(topic, key, payload).whenComplete((res, ex) -> {
            if (ex != null) log.error("Kafka publish failed topic={}", topic, ex);
            else            log.info("Published {} key={} offset={}", topic, key, res.getRecordMetadata().offset());
        });
    }
}
