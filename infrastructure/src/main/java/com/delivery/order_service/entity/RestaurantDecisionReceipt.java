package com.delivery.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** Durable identity and payload proof for a restaurant decision applied to an order. */
@Entity
@Table(name = "restaurant_decision_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_restaurant_decision_order", columnNames = "order_id")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantDecisionReceipt {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private Long restaurantId;

    @Column(name = "decision", nullable = false, updatable = false, length = 16)
    private String decision;

    @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
    private String payloadFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
