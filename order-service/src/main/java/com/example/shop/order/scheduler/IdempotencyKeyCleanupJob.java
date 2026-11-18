package com.example.shop.order.scheduler;

import com.example.shop.order.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);
    private final IdempotencyKeyRepository repo;

    public IdempotencyKeyCleanupJob(IdempotencyKeyRepository repo) { this.repo = repo; }

    /** Раз в час удаляет ключи старше 24 ч. */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = repo.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Idempotency keys cleanup: deleted {} rows older than {}", deleted, cutoff);
        }
    }
}
