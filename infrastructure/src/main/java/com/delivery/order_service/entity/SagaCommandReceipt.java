package com.delivery.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable Kafka inbox identity for a Saga command applied to an Order.
 *
 * <p>Order state transitions provide semantic convergence, but they cannot
 * distinguish an exact redelivery from a contradictory reuse of a committed
 * Kafka event identity. This receipt is committed with the Order mutation so
 * an offset replay cannot overwrite an already-applied command.</p>
 */
@Entity
@Table(name = "saga_command_receipts", indexes = {
        @Index(name = "idx_saga_command_receipts_order", columnList = "order_id,received_at")
})
@Getter
@NoArgsConstructor
public class SagaCommandReceipt {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "command_type", nullable = false, updatable = false, length = 64)
    private String commandType;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "saga_status", nullable = false, updatable = false, length = 64)
    private String sagaStatus;

    @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
    private String payloadFingerprint;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    public SagaCommandReceipt(UUID eventId, String commandType, Long orderId,
                              String sagaStatus, String payloadFingerprint) {
        this.eventId = eventId;
        this.commandType = commandType;
        this.orderId = orderId;
        this.sagaStatus = sagaStatus;
        this.payloadFingerprint = payloadFingerprint;
        this.receivedAt = LocalDateTime.now();
    }
}
