package com.example.shop.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
        SELECT * FROM outbox
        WHERE status IN ('PENDING','FAILED') AND retry_count < 3
        ORDER BY id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :cutoff")
    int deletePublishedOlderThan(@Param("cutoff") Instant cutoff);
}
