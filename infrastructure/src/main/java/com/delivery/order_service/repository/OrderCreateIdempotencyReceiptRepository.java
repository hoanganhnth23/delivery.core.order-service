package com.delivery.order_service.repository;

import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderCreateIdempotencyReceiptRepository
        extends JpaRepository<OrderCreateIdempotencyReceipt, Long> {

    Optional<OrderCreateIdempotencyReceipt> findByPrincipalIdAndIdempotencyKey(
            Long principalId, UUID idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from OrderCreateIdempotencyReceipt receipt "
            + "where receipt.principalId = :principalId and receipt.idempotencyKey = :idempotencyKey")
    Optional<OrderCreateIdempotencyReceipt> findByPrincipalIdAndIdempotencyKeyForUpdate(
            @Param("principalId") Long principalId, @Param("idempotencyKey") UUID idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO order_create_idempotency_receipts
                (principal_id, idempotency_key, request_fingerprint, fingerprint_version, created_at)
            VALUES (:principalId, :idempotencyKey, :requestFingerprint, :fingerprintVersion, CURRENT_TIMESTAMP)
            ON CONFLICT (principal_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentPostgres(@Param("principalId") Long principalId,
                               @Param("idempotencyKey") UUID idempotencyKey,
                               @Param("requestFingerprint") String requestFingerprint,
                               @Param("fingerprintVersion") String fingerprintVersion);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO order_create_idempotency_receipts
                (principal_id, idempotency_key, request_fingerprint, fingerprint_version, created_at)
            SELECT :principalId, :idempotencyKey, :requestFingerprint, :fingerprintVersion, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (
                SELECT 1 FROM order_create_idempotency_receipts
                WHERE principal_id = :principalId AND idempotency_key = :idempotencyKey
            )
            """, nativeQuery = true)
    int insertIfAbsentH2(@Param("principalId") Long principalId,
                         @Param("idempotencyKey") UUID idempotencyKey,
                         @Param("requestFingerprint") String requestFingerprint,
                         @Param("fingerprintVersion") String fingerprintVersion);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO order_create_idempotency_receipts
                (principal_id, idempotency_key, request_fingerprint, fingerprint_version,
                 processing_token, processing_until, created_at)
            VALUES (:principalId, :idempotencyKey, :requestFingerprint, :fingerprintVersion,
                    :processingToken, :processingUntil, CURRENT_TIMESTAMP)
            ON CONFLICT (principal_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsentWithLeasePostgres(@Param("principalId") Long principalId,
                                        @Param("idempotencyKey") UUID idempotencyKey,
                                        @Param("requestFingerprint") String requestFingerprint,
                                        @Param("fingerprintVersion") String fingerprintVersion,
                                        @Param("processingToken") UUID processingToken,
                                        @Param("processingUntil") Instant processingUntil);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO order_create_idempotency_receipts
                (principal_id, idempotency_key, request_fingerprint, fingerprint_version,
                 processing_token, processing_until, created_at)
            SELECT :principalId, :idempotencyKey, :requestFingerprint, :fingerprintVersion,
                   :processingToken, :processingUntil, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (
                SELECT 1 FROM order_create_idempotency_receipts
                WHERE principal_id = :principalId AND idempotency_key = :idempotencyKey
            )
            """, nativeQuery = true)
    int insertIfAbsentWithLeaseH2(@Param("principalId") Long principalId,
                                  @Param("idempotencyKey") UUID idempotencyKey,
                                  @Param("requestFingerprint") String requestFingerprint,
                                  @Param("fingerprintVersion") String fingerprintVersion,
                                  @Param("processingToken") UUID processingToken,
                                  @Param("processingUntil") Instant processingUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE order_create_idempotency_receipts
               SET processing_token = :processingToken,
                   processing_until = :processingUntil
             WHERE principal_id = :principalId
               AND idempotency_key = :idempotencyKey
               AND request_fingerprint = :requestFingerprint
               AND fingerprint_version = :fingerprintVersion
               AND order_id IS NULL
               AND (processing_until IS NULL OR processing_until <= CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int claimExpiredLeasePostgres(@Param("principalId") Long principalId,
                                  @Param("idempotencyKey") UUID idempotencyKey,
                                  @Param("requestFingerprint") String requestFingerprint,
                                  @Param("fingerprintVersion") String fingerprintVersion,
                                  @Param("processingToken") UUID processingToken,
                                  @Param("processingUntil") Instant processingUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE order_create_idempotency_receipts
               SET processing_token = :processingToken,
                   processing_until = :processingUntil
             WHERE principal_id = :principalId
               AND idempotency_key = :idempotencyKey
               AND request_fingerprint = :requestFingerprint
               AND fingerprint_version = :fingerprintVersion
               AND order_id IS NULL
               AND (processing_until IS NULL OR processing_until <= CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int claimExpiredLeaseH2(@Param("principalId") Long principalId,
                            @Param("idempotencyKey") UUID idempotencyKey,
                            @Param("requestFingerprint") String requestFingerprint,
                            @Param("fingerprintVersion") String fingerprintVersion,
                            @Param("processingToken") UUID processingToken,
                            @Param("processingUntil") Instant processingUntil);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE order_create_idempotency_receipts
               SET processing_token = NULL, processing_until = NULL
             WHERE id = :receiptId AND order_id IS NULL
               AND processing_token = :processingToken
            """, nativeQuery = true)
    int releaseLease(@Param("receiptId") Long receiptId,
                     @Param("processingToken") UUID processingToken);

    long deleteByCreatedAtBefore(Instant cutoff);
}
