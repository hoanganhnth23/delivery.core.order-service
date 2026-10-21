package com.delivery.order_service.repository;

import com.delivery.order_service.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT event.*
            FROM outbox_events event
            WHERE (
                    (event.status = 'PENDING' AND event.next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (event.status = 'IN_FLIGHT'
                        AND (event.lease_until IS NULL OR event.lease_until <= CURRENT_TIMESTAMP))
                  )
              AND NOT EXISTS (
                    SELECT 1
                    FROM outbox_events predecessor
                    WHERE predecessor.aggregate_type = event.aggregate_type
                      AND predecessor.aggregate_id = event.aggregate_id
                      AND predecessor.status IN ('PENDING', 'IN_FLIGHT', 'DEAD')
                      AND (predecessor.created_at < event.created_at
                        OR (predecessor.created_at = event.created_at AND predecessor.id < event.id))
                  )
            ORDER BY event.next_attempt_at, event.created_at, event.id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextClaimableBatch(@Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from OutboxEvent event where event.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);
}
