package com.example.shop.billing.kafka;

import com.example.shop.billing.model.Account;
import com.example.shop.billing.model.ProcessedOrder;
import com.example.shop.billing.repository.AccountRepository;
import com.example.shop.billing.repository.ProcessedOrderRepository;
import com.example.shop.billing.service.BillingEventPublisher;
import com.example.shop.common.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
    private final AccountRepository accountRepository;
    private final ProcessedOrderRepository processedOrderRepository;
    private final BillingEventPublisher publisher;

    public OrderEventsListener(AccountRepository accountRepository,
                               ProcessedOrderRepository processedOrderRepository,
                               BillingEventPublisher publisher) {
        this.accountRepository = accountRepository;
        this.processedOrderRepository = processedOrderRepository;
        this.publisher = publisher;
    }

    @KafkaListener(
            topics = Topics.ORDER_CREATED,
            groupId = "billing-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.OrderCreatedEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    @Transactional
    public void onOrderCreated(OrderCreatedEvent e) {
        if (e == null || e.orderId() == null) return;
        if (processedOrderRepository.existsById(e.orderId())) {
            log.info("Order {} already processed — skipping", e.orderId());
            return;
        }

        Account acc = accountRepository.findByUserId(e.userId()).orElse(null);
        if (acc == null) {
            publisher.publishFundsWithdrawFailed(new FundsWithdrawFailedEvent(
                    e.orderId(), e.userId(), null, e.price(), "NO_ACCOUNT"));
            processedOrderRepository.save(new ProcessedOrder(e.orderId()));
            log.warn("No account for userId={} orderId={}", e.userId(), e.orderId());
            return;
        }

        if (acc.getBalance().compareTo(e.price()) < 0) {
            publisher.publishFundsWithdrawFailed(new FundsWithdrawFailedEvent(
                    e.orderId(), e.userId(), acc.getId(), e.price(), "INSUFFICIENT_FUNDS"));
            processedOrderRepository.save(new ProcessedOrder(e.orderId()));
            log.info("Insufficient funds userId={} orderId={} balance={} price={}",
                     e.userId(), e.orderId(), acc.getBalance(), e.price());
            return;
        }

        acc.setBalance(acc.getBalance().subtract(e.price()));
        accountRepository.save(acc);
        processedOrderRepository.save(new ProcessedOrder(e.orderId()));
        publisher.publishFundsWithdrawn(new FundsWithdrawnEvent(
                e.orderId(), e.userId(), acc.getId(), e.price(), acc.getBalance()));
        log.info("Withdrawn {} from userId={} newBalance={} orderId={}",
                 e.price(), e.userId(), acc.getBalance(), e.orderId());
    }
}
