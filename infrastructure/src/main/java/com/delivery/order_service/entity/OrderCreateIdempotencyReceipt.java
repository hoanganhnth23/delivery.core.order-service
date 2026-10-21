package com.delivery.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Local command receipt: one customer create-order intent can complete once. */
@Entity
@Table(name = "order_create_idempotency_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_create_idempotency_principal_key",
                columnNames = {"principal_id", "idempotency_key"})
}, indexes = {
        @Index(name = "idx_order_create_idempotency_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor
public class OrderCreateIdempotencyReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private Long principalId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "fingerprint_version", nullable = false, updatable = false, length = 16)
    private String fingerprintVersion;

    @Column(name = "order_id")
    private Long orderId;

    /** Short-lived owner fence used while remote create-order preflight runs. */
    @Column(name = "processing_token")
    private UUID processingToken;

    @Column(name = "processing_until")
    private Instant processingUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OrderCreateIdempotencyReceipt(Long principalId, UUID idempotencyKey,
                                         String requestFingerprint, String fingerprintVersion,
                                         Instant createdAt) {
        this.principalId = principalId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.createdAt = createdAt;
    }

    public void complete(Long orderId) {
        this.orderId = orderId;
        this.processingToken = null;
        this.processingUntil = null;
    }
}
