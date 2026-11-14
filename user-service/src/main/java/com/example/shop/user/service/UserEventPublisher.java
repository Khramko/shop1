package com.example.shop.user.service;

import com.example.shop.common.events.Topics;
import com.example.shop.common.events.UserCreatedEvent;
import com.example.shop.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);

    private final KafkaTemplate<String, Object> kafka;

    public UserEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publishUserCreated(User user) {
        UserCreatedEvent event = new UserCreatedEvent(user.getId(), user.getEmail(), user.getName());
        kafka.send(Topics.USER_CREATED, user.getId().toString(), event)
             .whenComplete((res, ex) -> {
                 if (ex != null) {
                     log.error("Failed to publish {} for userId={}", Topics.USER_CREATED, user.getId(), ex);
                 } else {
                     log.info("Published {} userId={} partition={} offset={}",
                             Topics.USER_CREATED, user.getId(),
                             res.getRecordMetadata().partition(),
                             res.getRecordMetadata().offset());
                 }
             });
    }
}
