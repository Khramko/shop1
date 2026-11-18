package com.example.shop.order.service;

import com.example.shop.order.model.IdempotencyKey;
import com.example.shop.order.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyPlaceholderService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPlaceholderService.class);
    private final IdempotencyKeyRepository repo;

    public IdempotencyPlaceholderService(IdempotencyKeyRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsert(Long userId, String key) {
        try {
            repo.saveAndFlush(new IdempotencyKey(userId, key));
            return true;
        } catch (DataIntegrityViolationException race) {
            log.info("Idempotency placeholder race userId={} key={}", userId, key);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateResult(Long userId, String key, Long orderId, String body, int status) {
        int n = repo.updateResult(userId, key, orderId, body, status);
        if (n != 1) throw new IllegalStateException(
                "Idempotency update affected " + n + " rows for key=" + key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdempotencyKey awaitFilled(Long userId, String key, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            IdempotencyKey k = repo.findByUserIdAndKeyForUpdate(userId, key).orElse(null);
            if (k != null && k.getHttpStatus() != null && k.getOrderId() != null) return k;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return null;
            }
        }
        return null;
    }
}
