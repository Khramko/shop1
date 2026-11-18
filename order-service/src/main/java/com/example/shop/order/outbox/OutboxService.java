package com.example.shop.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {

    private final OutboxRepository repo;
    private final ObjectMapper mapper;

    public OutboxService(OutboxRepository repo, ObjectMapper mapper) {
        this.repo = repo; this.mapper = mapper;
    }

    public void enqueue(String aggregateType, String aggregateId,
                        String eventType, String topic, Object payload) {
        try {
            repo.save(new OutboxEvent(aggregateType, aggregateId, eventType, topic,
                                      mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize outbox payload " + eventType, ex);
        }
    }
}
