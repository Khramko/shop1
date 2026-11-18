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
    private final com.example.shop.order.outbox.OutboxService outbox;
    private final IdempotencyPlaceholderService placeholder;

    @Value("${saga.enabled:true}")
    private boolean sagaEnabled;

    public OrderService(OrderRepository orderRepository,
                        SagaStateRepository sagaRepo,
                        IdempotencyKeyRepository idemRepo,
                        OrderEventPublisher v1Publisher,
                        KafkaTemplate<String, Object> kafka,
                        ObjectMapper objectMapper,
                        com.example.shop.order.outbox.OutboxService outbox,
                        IdempotencyPlaceholderService placeholder) {
        this.orderRepository = orderRepository;
        this.sagaRepo = sagaRepo;
        this.idemRepo = idemRepo;
        this.v1Publisher = v1Publisher;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
        this.placeholder = placeholder;
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

    @Transactional
    public ResponseEntity<?> createIdempotent(Long userId, CreateOrderRequest req, String idemKey) {
        // 1. Быстрый путь: ключ уже заполнен — replay
        Optional<IdempotencyKey> existing = idemRepo.findByUserIdAndIdempotencyKey(userId, idemKey);
        if (existing.isPresent() && existing.get().getHttpStatus() != null) {
            return replay(existing.get());
        }

        // 2. Пытаемся «застолбить» ключ в отдельной транзакции (REQUIRES_NEW)
        if (!placeholder.tryInsert(userId, idemKey)) {
            // 3. Кто-то уже вставил — ждём, пока он заполнит результат
            IdempotencyKey k = placeholder.awaitFilled(userId, idemKey, 5000);
            if (k == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(java.util.Map.of("error", "idempotency_in_progress",
                                "details", "Retry later with the same Idempotency-Key"));
            }
            return replay(k);
        }

        // 4. Placeholder наш — создаём заказ (Order + SagaState + outbox в одной транзакции)
        OrderResponse r = create(userId, req.getPrice(), req.getSku(), req.getQty(), req.getDeliverySlot());

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize order response", ex);
        }

        // 5. Заполняем placeholder в отдельной транзакции (REQUIRES_NEW)
        placeholder.updateResult(userId, idemKey, r.getOrderId(), bodyJson, 202);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(r);
    }

    private ResponseEntity<?> replay(IdempotencyKey k) {
        log.info("Idempotent replay userId={} key={} orderId={}",
                k.getUserId(), k.getIdempotencyKey(), k.getOrderId());
        return ResponseEntity.status(k.getHttpStatus())
                .header("X-Idempotent-Replay", "true")
                .header("Content-Type", "application/json")
                .body(k.getResponseBody());
    }


    private OrderResponse createSaga(Long userId, BigDecimal price, String sku, Integer qty, String deliverySlot) {
        Order o = orderRepository.save(new Order(userId, price, sku, qty, deliverySlot));
        o.setStatus(Order.Status.RESERVING);
        orderRepository.save(o);
        sagaRepo.save(new SagaState(o.getId()));
        outbox.enqueue("Order", o.getId().toString(), "OrderSagaStarted",
                Topics.ORDER_SAGA_STARTED,
                new OrderSagaStartedEvent(o.getId(), userId, sku, qty, deliverySlot, price));
        outbox.enqueue("Order", o.getId().toString(), "ReserveFundsCommand",
                Topics.FUNDS_RESERVE_COMMAND,
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