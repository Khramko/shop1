package com.example.shop.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Публикует outbox-строки в Kafka. payload хранится как готовый JSON-текст,
 * поэтому используется KafkaTemplate<String,String> со StringSerializer —
 * без повторной JSON-сериализации.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 100;
    private static final int MAX_RETRY = 3;

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository repo, KafkaTemplate<String, String> kafka) {
        this.repo = repo; this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.delay-ms:2000}")
    @Transactional
    public void publish() {
        List<OutboxEvent> batch = repo.lockPendingBatch(BATCH);
        for (OutboxEvent e : batch) {
            try {
                kafka.send(e.getTopic(), e.getAggregateId(), e.getPayload())
                     .get(5, TimeUnit.SECONDS);
                e.setStatus(OutboxEvent.Status.PUBLISHED);
                e.setPublishedAt(Instant.now());
            } catch (Exception ex) {
                e.setRetryCount(e.getRetryCount() + 1);
                e.setStatus(e.getRetryCount() >= MAX_RETRY
                        ? OutboxEvent.Status.DEAD_LETTER
                        : OutboxEvent.Status.FAILED);
                log.error("Outbox publish failed id={} topic={} retry={}",
                          e.getId(), e.getTopic(), e.getRetryCount(), ex);
            }
            repo.save(e);
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanup() {
        int n = repo.deletePublishedOlderThan(Instant.now().minusSeconds(7 * 24 * 3600));
        if (n > 0) log.info("Outbox cleanup: deleted {} published rows", n);
    }
}
