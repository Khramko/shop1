package com.example.shop.billing.kafka;

import com.example.shop.billing.model.Account;
import com.example.shop.billing.repository.AccountRepository;
import com.example.shop.common.events.Topics;
import com.example.shop.common.events.UserCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class UserEventsListener {

    private static final Logger log = LoggerFactory.getLogger(UserEventsListener.class);
    private final AccountRepository accountRepository;

    public UserEventsListener(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @KafkaListener(
            topics = Topics.USER_CREATED,
            groupId = "billing-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.UserCreatedEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    @Transactional
    public void onUserCreated(UserCreatedEvent event) {
        if (event == null || event.userId() == null) return;
        if (accountRepository.existsByUserId(event.userId())) {
            log.info("Account already exists for userId={} — skipping", event.userId());
            return;
        }
        Account acc = accountRepository.save(new Account(event.userId(), BigDecimal.ZERO));
        log.info("Account created id={} for userId={} email={}",
                 acc.getId(), event.userId(), event.email());
    }
}
