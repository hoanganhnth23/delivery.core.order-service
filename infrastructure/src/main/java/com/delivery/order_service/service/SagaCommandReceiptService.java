package com.delivery.order_service.service;

import com.delivery.order_service.entity.SagaCommandReceipt;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Owns exact-replay and contradictory-replay fencing for Saga→Order commands. */
@Service
public class SagaCommandReceiptService {

    public static final String UPDATE_ORDER_STATUS = "UPDATE_ORDER_STATUS";

    private final SagaCommandReceiptRepository repository;

    @Value("${spring.datasource.url:}")
    private String dataSourceUrl;

    public SagaCommandReceiptService(SagaCommandReceiptRepository repository) {
        this.repository = repository;
    }

    /**
     * @return {@code true} only for a command not yet committed. Exact replay
     * returns {@code false}; a reused identity with different content fails
     * closed and is handled by the listener retry/DLT policy.
     */
    @Transactional
    public boolean claim(UUID eventId, String commandType, Long orderId,
                         String sagaStatus, String rawPayload) {
        com.delivery.order.domain.SagaCommandIdentity.requireCommand(
                eventId, commandType, orderId, sagaStatus, rawPayload);

        String fingerprint = fingerprint(rawPayload);
        SagaCommandReceipt existing = repository.findById(eventId).orElse(null);
        if (existing == null) {
            // Atomic conflict handling makes parallel consumer replicas
            // converge to a no-op exact replay instead of emitting a transient
            // duplicate-key failure after the winner commits.
            if (insertIfAbsent(eventId, commandType, orderId, sagaStatus, fingerprint) == 1) {
                return true;
            }
            existing = repository.findById(eventId).orElseThrow(() ->
                    new IllegalStateException("Saga command receipt conflict resolved without a committed row"));
        }
        requireExactReplay(existing, commandType, orderId, sagaStatus, fingerprint);
        return false;
    }

    private int insertIfAbsent(UUID eventId, String commandType, Long orderId,
                               String sagaStatus, String fingerprint) {
        if (dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
            return repository.insertIfAbsentH2(eventId, commandType, orderId, sagaStatus, fingerprint);
        }
        return repository.insertIfAbsentPostgres(eventId, commandType, orderId, sagaStatus, fingerprint);
    }

    private void requireExactReplay(SagaCommandReceipt existing, String commandType, Long orderId,
                                    String sagaStatus, String fingerprint) {
        new com.delivery.order.domain.SagaCommandIdentity(existing.getCommandType(), existing.getOrderId(),
                existing.getSagaStatus(), existing.getPayloadFingerprint()).requireExactReplay(
                new com.delivery.order.domain.SagaCommandIdentity(commandType, orderId, sagaStatus, fingerprint));
    }

    private String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}
