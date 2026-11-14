package com.example.shop.billing.kafka;

import com.example.shop.billing.model.Account;
import com.example.shop.billing.model.ReservedFunds;
import com.example.shop.billing.repository.AccountRepository;
import com.example.shop.billing.repository.ReservedFundsRepository;
import com.example.shop.common.events.SagaEvents.*;
import com.example.shop.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SagaFundsListener {

    private static final Logger log = LoggerFactory.getLogger(SagaFundsListener.class);
    private final AccountRepository accountRepo;
    private final ReservedFundsRepository reservedRepo;
    private final KafkaTemplate<String, Object> kafka;

    public SagaFundsListener(AccountRepository accountRepo,
                             ReservedFundsRepository reservedRepo,
                             KafkaTemplate<String, Object> kafka) {
        this.accountRepo = accountRepo;
        this.reservedRepo = reservedRepo;
        this.kafka = kafka;
    }

    @KafkaListener(
        topics = Topics.FUNDS_RESERVE_COMMAND, groupId = "billing-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReserveFundsCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onReserve(ReserveFundsCommand cmd) {
        if (reservedRepo.existsById(cmd.orderId())) {
            log.info("Funds for order {} already reserved — idempotent skip", cmd.orderId());
            Account acc = accountRepo.findByUserId(cmd.userId()).orElseThrow();
            kafka.send(Topics.FUNDS_RESERVED, cmd.orderId().toString(),
                new FundsReservedEvent(cmd.orderId(), acc.getId(), acc.getBalance()));
            return;
        }
        Account acc = accountRepo.findByUserId(cmd.userId()).orElse(null);
        if (acc == null) {
            kafka.send(Topics.FUNDS_RESERVE_FAILED, cmd.orderId().toString(),
                new FundsReserveFailedEvent(cmd.orderId(), "NO_ACCOUNT"));
            return;
        }
        if (acc.getBalance().compareTo(cmd.amount()) < 0) {
            kafka.send(Topics.FUNDS_RESERVE_FAILED, cmd.orderId().toString(),
                new FundsReserveFailedEvent(cmd.orderId(), "INSUFFICIENT_FUNDS"));
            return;
        }
        acc.setBalance(acc.getBalance().subtract(cmd.amount()));
        accountRepo.save(acc);
        reservedRepo.save(new ReservedFunds(cmd.orderId(), cmd.userId(), cmd.amount()));
        kafka.send(Topics.FUNDS_RESERVED, cmd.orderId().toString(),
            new FundsReservedEvent(cmd.orderId(), acc.getId(), acc.getBalance()));
        log.info("Reserved funds order={} amount={} balance={}", cmd.orderId(), cmd.amount(), acc.getBalance());
    }

    @KafkaListener(
        topics = Topics.FUNDS_RELEASE_COMMAND, groupId = "billing-service",
        properties = {
            "spring.json.value.default.type=com.example.shop.common.events.SagaEvents$ReleaseFundsCommand",
            "spring.json.use.type.headers=false",
            "spring.json.trusted.packages=com.example.shop.common.events"
        })
    @Transactional
    public void onRelease(ReleaseFundsCommand cmd) {
        ReservedFunds rf = reservedRepo.findById(cmd.orderId()).orElse(null);
        if (rf == null) {
            kafka.send(Topics.FUNDS_RELEASED, cmd.orderId().toString(),
                new FundsReleasedEvent(cmd.orderId()));
            log.info("Release order={} — nothing to release (idempotent)", cmd.orderId());
            return;
        }
        Account acc = accountRepo.findByUserId(rf.getUserId()).orElseThrow();
        acc.setBalance(acc.getBalance().add(rf.getAmount()));
        accountRepo.save(acc);
        reservedRepo.delete(rf);
        kafka.send(Topics.FUNDS_RELEASED, cmd.orderId().toString(),
            new FundsReleasedEvent(cmd.orderId()));
        log.info("Released funds order={} amount={} balance={}", cmd.orderId(), rf.getAmount(), acc.getBalance());
    }
}
