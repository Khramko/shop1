package com.example.shop.order.service;

import com.example.shop.common.events.SagaEvents.OrderSagaStartedEvent;
import com.example.shop.common.events.SagaEvents.ReserveFundsCommand;
import com.example.shop.common.events.Topics;
import com.example.shop.order.dto.OrderResponse;
import com.example.shop.order.model.Order;
import com.example.shop.order.model.SagaState;
import com.example.shop.order.repository.OrderRepository;
import com.example.shop.order.repository.SagaStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaRepo;
    private final OrderEventPublisher v1Publisher;
    private final KafkaTemplate<String, Object> kafka;

    @Value("${saga.enabled:true}")
    private boolean sagaEnabled;

    public OrderService(OrderRepository orderRepository, SagaStateRepository sagaRepo,
                        OrderEventPublisher v1Publisher, KafkaTemplate<String, Object> kafka) {
        this.orderRepository = orderRepository;
        this.sagaRepo = sagaRepo;
        this.v1Publisher = v1Publisher;
        this.kafka = kafka;
    }

    public boolean isSagaEnabled() { return sagaEnabled; }

    @Transactional
    public OrderResponse create(Long userId, BigDecimal price, String sku, Integer qty, String deliverySlot) {
        if (sagaEnabled) {
            return createSaga(userId, price, sku, qty, deliverySlot);
        }
        return createV1(userId, price);
    }

    private OrderResponse createSaga(Long userId, BigDecimal price, String sku, Integer qty, String deliverySlot) {
        Order o = orderRepository.save(new Order(userId, price, sku, qty, deliverySlot));
        o.setStatus(Order.Status.RESERVING);
        orderRepository.save(o);
        sagaRepo.save(new SagaState(o.getId()));
        kafka.send(Topics.ORDER_SAGA_STARTED, o.getId().toString(),
            new OrderSagaStartedEvent(o.getId(), userId, sku, qty, deliverySlot, price));
        kafka.send(Topics.FUNDS_RESERVE_COMMAND, o.getId().toString(),
            new ReserveFundsCommand(o.getId(), userId, price));
        log.info("Saga[{}] started (funds → stock → courier)", o.getId());
        return OrderResponse.from(o);
    }

    private OrderResponse createV1(Long userId, BigDecimal price) {
        Order o = orderRepository.save(new Order(userId, price, null, null, null));
        o.setStatus(Order.Status.NEW);
        orderRepository.save(o);
        v1Publisher.publishOrderCreated(o);
        log.info("Order[{}] created (v1 async), price={}", o.getId(), price);
        return OrderResponse.from(o);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId).map(OrderResponse::from).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listByUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderResponse::from).toList();
    }
}
