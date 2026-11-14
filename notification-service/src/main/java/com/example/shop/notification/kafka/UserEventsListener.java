package com.example.shop.notification.kafka;

import com.example.shop.common.events.Topics;
import com.example.shop.common.events.UserCreatedEvent;
import com.example.shop.notification.model.UserEmailProjection;
import com.example.shop.notification.repository.UserEmailProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserEventsListener {

    private static final Logger log = LoggerFactory.getLogger(UserEventsListener.class);
    private final UserEmailProjectionRepository emailRepo;

    public UserEventsListener(UserEmailProjectionRepository emailRepo) { this.emailRepo = emailRepo; }

    @KafkaListener(
            topics = Topics.USER_CREATED,
            groupId = "notification-service",
            properties = {
                    "spring.json.value.default.type=com.example.shop.common.events.UserCreatedEvent",
                    "spring.json.use.type.headers=false",
                    "spring.json.trusted.packages=com.example.shop.common.events"
            }
    )
    @Transactional
    public void onUserCreated(UserCreatedEvent e) {
        if (e == null || e.userId() == null) return;
        if (emailRepo.existsById(e.userId())) {
            log.info("Email projection already exists userId={} — skipping", e.userId());
            return;
        }
        emailRepo.save(new UserEmailProjection(e.userId(), e.email()));
        log.info("Email projection saved userId={} email={}", e.userId(), e.email());
    }
}
