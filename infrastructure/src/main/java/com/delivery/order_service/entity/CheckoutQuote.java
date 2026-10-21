package com.delivery.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Durable, principal-scoped evidence of a server-calculated checkout price. */
@Entity
@Table(name = "checkout_quotes", indexes = {
        @Index(name = "idx_checkout_quotes_principal_expiry", columnList = "principal_id,expires_at"),
        @Index(name = "idx_checkout_quotes_expiry", columnList = "expires_at")
})
@Getter
@NoArgsConstructor
public class CheckoutQuote {

    @Id
    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private Long principalId;

    @Column(name = "pricing_input_fingerprint", nullable = false, updatable = false, length = 64)
    private String pricingInputFingerprint;

    @Column(name = "pricing_fingerprint", nullable = false, updatable = false, length = 64)
    private String pricingFingerprint;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_order_id")
    private Long consumedOrderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CheckoutQuote(UUID quoteId, Long principalId, String pricingInputFingerprint,
                         String pricingFingerprint, Instant expiresAt, Instant createdAt) {
        this.quoteId = quoteId;
        this.principalId = principalId;
        this.pricingInputFingerprint = pricingInputFingerprint;
        this.pricingFingerprint = pricingFingerprint;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public void consume(Long orderId) {
        this.consumedOrderId = orderId;
    }
}
