package com.example.shop.order.repository;

import com.example.shop.order.model.IdempotencyKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String key);

    /** Читает строку с блокировкой — чтобы awaitFilled не увидел «полузаполненный» placeholder. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKey k WHERE k.userId = :userId AND k.idempotencyKey = :key")
    Optional<IdempotencyKey> findByUserIdAndKeyForUpdate(
            @Param("userId") Long userId, @Param("key") String key);

    /** Заполняет placeholder результатом заказа. Возвращает число обновлённых строк (должно быть 1). */
    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.orderId = :orderId, k.responseBody = :body, " +
            "k.httpStatus = :status WHERE k.userId = :userId AND k.idempotencyKey = :key")
    int updateResult(@Param("userId") Long userId, @Param("key") String key,
                     @Param("orderId") Long orderId, @Param("body") String body,
                     @Param("status") int status);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}