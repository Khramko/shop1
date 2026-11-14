package com.example.shop.order.service;

import com.example.shop.common.events.SagaEvents.OrderSagaStartedEvent;
import com.example.shop.common.events.SagaEvents.ReserveFundsCommand;
import com.example.shop.common.events.Topics;
import com.example.shop.order.dto.CreateOrderRequest;
import com.example.shop.order.dto.OrderResponse;
import com.example.shop.order.model.IdempotencyKey;
import com.example.shop.order.model.Order;
import com.example.shop.order.model.SagaState;
import com.example.shop.order.repository.IdempotencyKeyRepository;
import com.example.shop.order.repository.OrderRepository;
import com.example.shop.order.repository.SagaStateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaRepo;
    private final IdempotencyKeyRepository idemRepo;
    private final OrderEventPublisher v1Publisher;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;

    @Value("${saga.enabled:true}")
    private boolean sagaEnabled;

    public OrderService(OrderRepository orderRepository,
                        SagaStateRepository sagaRepo,
                        IdempotencyKeyRepository idemRepo,
                        OrderEventPublisher v1Publisher,
                        KafkaTemplate<String, Object> kafka,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.sagaRepo = sagaRepo;
        this.idemRepo = idemRepo;
        this.v1Publisher = v1Publisher;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public boolean isSagaEnabled() { return sagaEnabled; }

    // ============================================================
    //  Существующий create — без изменений
    // ============================================================
    @Transactional
    public OrderResponse create(Long userId, BigDecimal price, String sku, Integer qty, String deliverySlot) {
        if (sagaEnabled) {
            return createSaga(userId, price, sku, qty, deliverySlot);
        }
        return createV1(userId, price);
    }

    // ============================================================
    //  NEW: идемпотентный create с Idempotency-Key
    // ============================================================
    @Transactional
    public ResponseEntity<?> createIdempotent(Long userId, CreateOrderRequest req, String idemKey) {
        Optional<IdempotencyKey> existing = idemRepo.findByUserIdAndIdempotencyKey(userId, idemKey);
        if (existing.isPresent()) {
            IdempotencyKey k = existing.get();
            log.info("Idempotent replay userId={} key={} orderId={}", userId, idemKey, k.getOrderId());
            return ResponseEntity.status(k.getHttpStatus())
                    .header("X-Idempotent-Replay", "true")
                    .header("Content-Type", "application/json")
                    .body(k.getResponseBody());
        }

        OrderResponse r = create(userId, req.getPrice(), req.getSku(), req.getQty(), req.getDeliverySlot());

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize order response", ex);
        }

        try {
            idemRepo.save(new IdempotencyKey(userId, idemKey, r.getOrderId(), bodyJson, 202));
        } catch (DataIntegrityViolationException race) {
            log.warn("Idempotency race userId={} key={} — reading existing", userId, idemKey);
            IdempotencyKey k = idemRepo.findByUserIdAndIdempotencyKey(userId, idemKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key vanished after race"));
            return ResponseEntity.status(k.getHttpStatus())
                    .header("X-Idempotent-Replay", "true")
                    .header("Content-Type", "application/json")
                    .body(k.getResponseBody());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(r);
    }

    // ============================================================
    //  Приватные методы (без изменений)
    // ============================================================
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