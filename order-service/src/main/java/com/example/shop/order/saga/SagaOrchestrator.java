package com.example.shop.order.saga;

import com.example.shop.common.events.SagaEvents.*;
import com.example.shop.common.events.Topics;
import com.example.shop.order.model.Order;
import com.example.shop.order.model.SagaState;
import com.example.shop.order.repository.OrderRepository;
import com.example.shop.order.repository.SagaStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private final OrderRepository orderRepo;
    private final SagaStateRepository sagaRepo;
    private final KafkaTemplate<String, Object> kafka;

    public SagaOrchestrator(OrderRepository orderRepo, SagaStateRepository sagaRepo,
                            KafkaTemplate<String, Object> kafka) {
        this.orderRepo = orderRepo; this.sagaRepo = sagaRepo; this.kafka = kafka;
    }

    private void send(String topic, Long orderId, Object payload) {
        kafka.send(topic, orderId.toString(), payload).whenComplete((r, ex) -> {
            if (ex != null) log.error("Kafka send failed topic={}", topic, ex);
        });
    }

    // --- Callbacks from billing ---
    @KafkaListener(topics = Topics.FUNDS_RESERVED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$FundsReservedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onFundsReserved(FundsReservedEvent e) {
        SagaState st = sagaRepo.findById(e.orderId()).orElse(null);
        if (st == null || st.getStep() != SagaState.Step.STARTED) return;
        st.setStep(SagaState.Step.FUNDS_RESERVED); st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(e.orderId()).orElseThrow();
        log.info("Saga[{}] funds reserved → reserve stock", e.orderId());
        send(Topics.STOCK_RESERVE_COMMAND, e.orderId(), new ReserveStockCommand(e.orderId(), o.getSku(), o.getQty()));
    }

    @KafkaListener(topics = Topics.FUNDS_RESERVE_FAILED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$FundsReserveFailedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onFundsFailed(FundsReserveFailedEvent e) {
        failSaga(e.orderId(), "FUNDS:" + e.reason(), SagaState.Step.STARTED);
    }

    // --- Callbacks from warehouse ---
    @KafkaListener(topics = Topics.STOCK_RESERVED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$StockReservedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onStockReserved(StockReservedEvent e) {
        SagaState st = sagaRepo.findById(e.orderId()).orElse(null);
        if (st == null || st.getStep() != SagaState.Step.FUNDS_RESERVED) return;
        st.setStep(SagaState.Step.STOCK_RESERVED); st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(e.orderId()).orElseThrow();
        log.info("Saga[{}] stock reserved → reserve courier", e.orderId());
        send(Topics.COURIER_RESERVE_COMMAND, e.orderId(), new ReserveCourierCommand(e.orderId(), o.getDeliverySlot()));
    }

    @KafkaListener(topics = Topics.STOCK_RESERVE_FAILED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$StockReserveFailedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onStockFailed(StockReserveFailedEvent e) {
        compensate(e.orderId(), "STOCK:" + e.reason(), SagaState.Step.FUNDS_RESERVED);
    }

    // --- Callbacks from delivery ---
    @KafkaListener(topics = Topics.COURIER_RESERVED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$CourierReservedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onCourierReserved(CourierReservedEvent e) {
        SagaState st = sagaRepo.findById(e.orderId()).orElse(null);
        if (st == null || st.getStep() != SagaState.Step.STOCK_RESERVED) return;
        st.setStep(SagaState.Step.DONE); st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(e.orderId()).orElseThrow();
        o.setStatus(Order.Status.PAID);
        orderRepo.save(o);
        log.info("Saga[{}] DONE → order PAID", e.orderId());
        // v1 событие для notification
        kafka.send(Topics.ORDER_PAID, e.orderId().toString(),
            new com.example.shop.common.events.OrderPaidEvent(e.orderId(), o.getUserId(), o.getPrice()));
    }

    @KafkaListener(topics = Topics.COURIER_RESERVE_FAILED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$CourierReserveFailedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onCourierFailed(CourierReserveFailedEvent e) {
        compensate(e.orderId(), "COURIER:" + e.reason(), SagaState.Step.STOCK_RESERVED);
    }

    // --- Compensation callbacks ---
    @KafkaListener(topics = Topics.STOCK_RELEASED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$StockReleasedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onStockReleased(StockReleasedEvent e) {
        SagaState st = sagaRepo.findById(e.orderId()).orElse(null);
        if (st == null || st.getStep() != SagaState.Step.COMPENSATING) return;
        Order o = orderRepo.findById(e.orderId()).orElseThrow();
        send(Topics.FUNDS_RELEASE_COMMAND, e.orderId(),
            new ReleaseFundsCommand(e.orderId(), o.getUserId(), o.getPrice()));
    }

    @KafkaListener(topics = Topics.FUNDS_RELEASED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$FundsReleasedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onFundsReleased(FundsReleasedEvent e) {
        SagaState st = sagaRepo.findById(e.orderId()).orElse(null);
        if (st == null || st.getStep() != SagaState.Step.COMPENSATING) return;
        st.setStep(SagaState.Step.FAILED); st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(e.orderId()).orElseThrow();
        o.setStatus(Order.Status.FAILED);
        orderRepo.save(o);
        log.info("Saga[{}] FAILED after compensation", e.orderId());
        kafka.send(Topics.ORDER_FAILED, e.orderId().toString(),
            new com.example.shop.common.events.OrderFailedEvent(
                e.orderId(), o.getUserId(), o.getPrice(), st.getFailureReason()));
    }

    @KafkaListener(topics = Topics.STOCK_RELEASE_FAILED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$StockReleaseFailedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onStockReleaseFailed(StockReleaseFailedEvent e) {
        markCompensationFailed(e.orderId(), "STOCK_RELEASE:" + e.reason());
    }

    @KafkaListener(topics = Topics.FUNDS_RELEASE_FAILED, groupId = "order-service-saga",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$FundsReleaseFailedEvent",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onFundsReleaseFailed(FundsReleaseFailedEvent e) {
        markCompensationFailed(e.orderId(), "FUNDS_RELEASE:" + e.reason());
    }

    // --- helpers ---
    private void failSaga(Long orderId, String reason, SagaState.Step expectedStep) {
        SagaState st = sagaRepo.findById(orderId).orElse(null);
        if (st == null) return;
        st.setStep(SagaState.Step.FAILED); st.setFailureReason(reason);
        st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(orderId).orElseThrow();
        o.setStatus(Order.Status.FAILED); orderRepo.save(o);
        kafka.send(Topics.ORDER_FAILED, orderId.toString(),
            new com.example.shop.common.events.OrderFailedEvent(orderId, o.getUserId(), o.getPrice(), reason));
    }

    private void compensate(Long orderId, String reason, SagaState.Step doneStep) {
        SagaState st = sagaRepo.findById(orderId).orElse(null);
        if (st == null) return;
        st.setStep(SagaState.Step.COMPENSATING); st.setFailureReason(reason);
        st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(orderId).orElseThrow();
        o.setStatus(Order.Status.COMPENSATING); orderRepo.save(o);

        // Откат строго по тому, что успело зарезервироваться:
        //  - FAILED на billing (doneStep=STARTED): ничего не откатываем
        //  - FAILED на warehouse (doneStep=FUNDS_RESERVED): откат funds
        //  - FAILED на delivery (doneStep=STOCK_RESERVED): откат stock → затем funds (цепочкой через onStockReleased)
        //  courier.release НЕ шлём: провал delivery означает, что курьер не зарезервирован.
        if (doneStep == SagaState.Step.STOCK_RESERVED) {
            send(Topics.STOCK_RELEASE_COMMAND, orderId, new ReleaseStockCommand(orderId, o.getSku(), o.getQty()));
        } else if (doneStep == SagaState.Step.FUNDS_RESERVED) {
            send(Topics.FUNDS_RELEASE_COMMAND, orderId, new ReleaseFundsCommand(orderId, o.getUserId(), o.getPrice()));
        }
    }

    private void markCompensationFailed(Long orderId, String reason) {
        SagaState st = sagaRepo.findById(orderId).orElse(null);
        if (st == null) return;
        st.setStep(SagaState.Step.COMPENSATION_FAILED);
        st.setFailureReason(reason);
        st.setUpdatedAt(java.time.Instant.now()); sagaRepo.save(st);
        Order o = orderRepo.findById(orderId).orElse(null);
        if (o != null) {
            o.setStatus(Order.Status.COMPENSATION_FAILED);
            orderRepo.save(o);
        } else {
            log.warn("Saga[{}] COMPENSATION_FAILED: order not found (уже удалён?)", orderId);
        }
        log.error("Saga[{}] COMPENSATION_FAILED reason={}", orderId, reason);
    }
}
