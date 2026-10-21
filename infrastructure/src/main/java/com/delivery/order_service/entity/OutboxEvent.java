package com.delivery.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events",
        indexes = {
                @Index(name = "idx_order_outbox_pending",
                        columnList = "status,next_attempt_at,created_at,id"),
                @Index(name = "idx_order_outbox_aggregate",
                        columnList = "aggregate_type,aggregate_id,created_at")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_order_outbox_event_id",
                columnNames = "event_id"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    public enum Status { PENDING, IN_FLIGHT, SENT, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 128)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(name = "event_key", nullable = false, updatable = false)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Stable owner fence for a short relay lease. */
    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "last_error", length = 2000)
    private String lastError;
}
