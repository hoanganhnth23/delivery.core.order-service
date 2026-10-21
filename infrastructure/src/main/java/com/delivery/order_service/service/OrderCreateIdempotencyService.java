package com.delivery.order_service.service;

import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import com.delivery.order_service.exception.OrderApiException;
import com.delivery.order_service.repository.OrderCreateIdempotencyReceiptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Database-backed create-order retry fence. */
@Service
public class OrderCreateIdempotencyService {
    private final OrderCreateIdempotencyReceiptRepository repository;

    @Value("${spring.datasource.url:}")
    private String dataSourceUrl;

    private final Duration processingLease;

    @Autowired
    public OrderCreateIdempotencyService(OrderCreateIdempotencyReceiptRepository repository,
                                         @Value("${app.order.idempotency.processing-lease:PT30S}") Duration processingLease) {
        this.repository = repository;
        this.processingLease = com.delivery.order.domain.IdempotencyLeasePolicy.boundedLease(processingLease);
    }

    /** Source-compatible constructor for focused unit tests and old callers. */
    public OrderCreateIdempotencyService(OrderCreateIdempotencyReceiptRepository repository) {
        this(repository, Duration.ofSeconds(30));
    }

    /**
     * Claims the key before remote preflight. The claim is leased so a crashed
     * request cannot permanently strand the idempotency key.
     */
    @Transactional
    public OrderCreateIdempotencyReceipt acquire(Long principalId, UUID key, String fingerprint,
                                                  UUID processingToken) {
        return com.delivery.order.application.IdempotencyWorkflow.acquire(
                ports(principalId, key, fingerprint, processingToken));
    }

    public OrderCreateIdempotencyReceipt claim(Long principalId, UUID key, String fingerprint) {
        return com.delivery.order.application.IdempotencyWorkflow.legacyClaim(
                new com.delivery.order.application.api.LegacyIdempotencyPorts<OrderCreateIdempotencyReceipt>() {
            public OrderCreateIdempotencyReceipt find() {
                return repository.findByPrincipalIdAndIdempotencyKey(principalId, key).orElse(null);
            }
            public int insert() { return insertIfAbsent(principalId, key, fingerprint); }
            public OrderCreateIdempotencyReceipt requireFound() {
                return repository.findByPrincipalIdAndIdempotencyKey(principalId, key).orElseThrow();
            }
            public OrderCreateIdempotencyReceipt requireExisting() {
                return repository.findByPrincipalIdAndIdempotencyKey(principalId, key).orElseThrow(() ->
                        new OrderApiException("IDEMPOTENCY_IN_PROGRESS", "Yêu cầu đặt đơn đang được xử lý"));
            }
            public void assertFingerprint(OrderCreateIdempotencyReceipt receipt) {
                // Preserve legacy null-fingerprint behavior and the original comparison direction.
                if (!com.delivery.order.domain.IdempotencyLeasePolicy.fingerprintMatches(
                        receipt.getRequestFingerprint(), fingerprint,
                        CheckoutFingerprintService.VERSION, receipt.getFingerprintVersion()))
                    throw new OrderApiException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key đã được dùng với dữ liệu khác");
            }
            public boolean completed(OrderCreateIdempotencyReceipt receipt) { return receipt.getOrderId() != null; }
            public RuntimeException inProgress() { return OrderCreateIdempotencyService.this.inProgress(); }
        });
    }

    /** Final transaction fence for a request that owns the preflight lease. */
    public OrderCreateIdempotencyReceipt claim(Long principalId, UUID key, String fingerprint,
                                               UUID processingToken) {
        return com.delivery.order.application.IdempotencyWorkflow.claim(
                ports(principalId, key, fingerprint, processingToken));
    }

    private com.delivery.order.application.api.IdempotencyPorts<OrderCreateIdempotencyReceipt> ports(
            Long principalId, UUID key, String fingerprint, UUID processingToken) {
        return new com.delivery.order.application.api.IdempotencyPorts<>() {
            private Instant processingUntil;
            public void requireArguments() {
                OrderCreateIdempotencyService.this.requireArguments(principalId, key, fingerprint, processingToken);
            }
            public OrderCreateIdempotencyReceipt find() {
                // Acquire computes this before its initial lookup, as before extraction.
                if (processingUntil == null) processingUntil = Instant.now().plus(processingLease);
                return repository.findByPrincipalIdAndIdempotencyKey(principalId, key).orElse(null);
            }
            public OrderCreateIdempotencyReceipt findLocked() {
                return repository.findByPrincipalIdAndIdempotencyKeyForUpdate(principalId, key).orElse(null);
            }
            public void assertFingerprint(OrderCreateIdempotencyReceipt receipt) {
                assertFingerprintMatches(receipt, fingerprint);
            }
            public boolean completed(OrderCreateIdempotencyReceipt receipt) { return receipt.getOrderId() != null; }
            public boolean ownedAndLive(OrderCreateIdempotencyReceipt receipt) {
                return isOwnedAndLive(receipt, processingToken);
            }
            public boolean live(OrderCreateIdempotencyReceipt receipt) { return isLive(receipt); }
            public int insert() {
                return insertIfAbsentWithLease(principalId, key, fingerprint, processingToken, processingUntil);
            }
            public int reclaim() {
                return claimExpiredLease(principalId, key, fingerprint, processingToken, processingUntil);
            }
            public OrderCreateIdempotencyReceipt requireFound() {
                return repository.findByPrincipalIdAndIdempotencyKey(principalId, key).orElseThrow();
            }
            public RuntimeException inProgress() { return OrderCreateIdempotencyService.this.inProgress(); }
        };
    }

    /**
     * Read-only fast path for a completed replay.  The actual claim still runs
     * inside the final order transaction, so this method is only an optimisation
     * and never the correctness boundary.
     */
    public Optional<OrderCreateIdempotencyReceipt> findExisting(Long principalId, UUID key) {
        if (principalId == null || key == null) {
            return Optional.empty();
        }
        return repository.findByPrincipalIdAndIdempotencyKey(principalId, key);
    }

    public void assertFingerprintMatches(OrderCreateIdempotencyReceipt receipt, String fingerprint) {
        if (receipt == null || fingerprint == null) {
            throw new IllegalArgumentException("Idempotency receipt and fingerprint are required");
        }
        if (!com.delivery.order.domain.IdempotencyLeasePolicy.fingerprintMatches(
                fingerprint, receipt.getRequestFingerprint(),
                CheckoutFingerprintService.VERSION, receipt.getFingerprintVersion())) {
            throw new OrderApiException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key đã được dùng với dữ liệu khác");
        }
    }

    public void complete(OrderCreateIdempotencyReceipt receipt, Long orderId) {
        receipt.complete(orderId);
    }

    @Transactional
    public void release(Long receiptId, UUID processingToken) {
        if (receiptId != null && processingToken != null) {
            repository.releaseLease(receiptId, processingToken);
        }
    }

    private int insertIfAbsent(Long principalId, UUID key, String fingerprint) {
        if (dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
            return repository.insertIfAbsentH2(principalId, key, fingerprint, CheckoutFingerprintService.VERSION);
        }
        return repository.insertIfAbsentPostgres(principalId, key, fingerprint, CheckoutFingerprintService.VERSION);
    }

    private int insertIfAbsentWithLease(Long principalId, UUID key, String fingerprint,
                                        UUID processingToken, Instant processingUntil) {
        if (dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
            return repository.insertIfAbsentWithLeaseH2(principalId, key, fingerprint,
                    CheckoutFingerprintService.VERSION, processingToken, processingUntil);
        }
        return repository.insertIfAbsentWithLeasePostgres(principalId, key, fingerprint,
                CheckoutFingerprintService.VERSION, processingToken, processingUntil);
    }

    private int claimExpiredLease(Long principalId, UUID key, String fingerprint,
                                  UUID processingToken, Instant processingUntil) {
        if (dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
            return repository.claimExpiredLeaseH2(principalId, key, fingerprint,
                    CheckoutFingerprintService.VERSION, processingToken, processingUntil);
        }
        return repository.claimExpiredLeasePostgres(principalId, key, fingerprint,
                CheckoutFingerprintService.VERSION, processingToken, processingUntil);
    }

    private void requireArguments(Long principalId, UUID key, String fingerprint, UUID processingToken) {
        if (principalId == null || key == null || fingerprint == null || processingToken == null) {
            throw new IllegalArgumentException("Idempotency claim arguments are required");
        }
    }

    private boolean isOwnedAndLive(OrderCreateIdempotencyReceipt receipt, UUID token) {
        return com.delivery.order.domain.IdempotencyLeasePolicy.owned(token, receipt.getProcessingToken()) && isLive(receipt);
    }

    private boolean isLive(OrderCreateIdempotencyReceipt receipt) {
        return receipt.getProcessingUntil() != null && com.delivery.order.domain.IdempotencyLeasePolicy.live(receipt.getProcessingUntil(), Instant.now());
    }

    private OrderApiException inProgress() {
        return new OrderApiException("IDEMPOTENCY_IN_PROGRESS",
                "Yêu cầu đặt đơn đang được xử lý, vui lòng thử lại với cùng Idempotency-Key");
    }

}
