package com.delivery.order_service.repository;

import com.delivery.order_service.entity.SagaCommandReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SagaCommandReceiptRepository extends JpaRepository<SagaCommandReceipt, UUID> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO saga_command_receipts (
                event_id, command_type, order_id, saga_status, payload_fingerprint, received_at
            ) VALUES (
                :eventId, :commandType, :orderId, :sagaStatus, :payloadFingerprint, CURRENT_TIMESTAMP
            ) ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentPostgres(
            @Param("eventId") UUID eventId,
            @Param("commandType") String commandType,
            @Param("orderId") Long orderId,
            @Param("sagaStatus") String sagaStatus,
            @Param("payloadFingerprint") String payloadFingerprint);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO saga_command_receipts (
                event_id, command_type, order_id, saga_status, payload_fingerprint, received_at
            ) SELECT
                :eventId, :commandType, :orderId, :sagaStatus, :payloadFingerprint, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (
                SELECT 1 FROM saga_command_receipts WHERE event_id = :eventId
            )
            """, nativeQuery = true)
    int insertIfAbsentH2(
            @Param("eventId") UUID eventId,
            @Param("commandType") String commandType,
            @Param("orderId") Long orderId,
            @Param("sagaStatus") String sagaStatus,
            @Param("payloadFingerprint") String payloadFingerprint);
}
