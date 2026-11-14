package com.example.shop.order.kafka;

import com.example.shop.common.events.*;
import com.example.shop.order.model.Order;
import com.example.shop.order.repository.OrderRepository;
import com.example.shop.order.service.OrderEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BillingEventsListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventsListener.class);
    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;

    public BillingEventsListener(OrderRepository orderRepository, OrderEventPublisher publisher) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    @KafkaListener(
            topics = Topics.FUNDS_WITHDRAWN,
            groupId = "order-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.FundsWithdrawnEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    @Transactional
    public void onFundsWithdrawn(FundsWithdrawnEvent e) {
        orderRepository.findById(e.orderId()).ifPresent(o -> {
            // v2-заказы ведёт SagaOrchestrator — v1 их не трогает
            if (o.getStatus() != Order.Status.NEW) {
                log.info("Order {} in status {} — v1 skip", o.getId(), o.getStatus());
                return;
            }
            o.setStatus(Order.Status.PAID);
            orderRepository.save(o);
            publisher.publishOrderPaid(o);
            log.info("Order {} PAID (v1) userId={} price={}", o.getId(), o.getUserId(), o.getPrice());
        });
    }

    @KafkaListener(
            topics = Topics.FUNDS_WITHDRAW_FAILED,
            groupId = "order-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.FundsWithdrawFailedEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    @Transactional
    public void onFundsWithdrawFailed(FundsWithdrawFailedEvent e) {
        orderRepository.findById(e.orderId()).ifPresent(o -> {
            if (o.getStatus() != Order.Status.NEW) {
                log.info("Order {} in status {} — v1 skip", o.getId(), o.getStatus());
                return;
            }
            o.setStatus(Order.Status.FAILED);
            orderRepository.save(o);
            publisher.publishOrderFailed(o, e.reason());
            log.info("Order {} FAILED (v1) reason={}", o.getId(), e.reason());
        });
    }
}
