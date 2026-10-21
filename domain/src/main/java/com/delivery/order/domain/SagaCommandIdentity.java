package com.delivery.order.domain;

import java.util.UUID;

/** Exact replay is bound to every stored command identity field and raw-payload fingerprint. */
public record SagaCommandIdentity(String commandType, Long orderId, String sagaStatus, String fingerprint) {
    public static void requireCommand(UUID eventId, String commandType, Long orderId,
                                      String sagaStatus, String rawPayload) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        requireText(commandType, "commandType");
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("orderId must be positive");
        requireText(sagaStatus, "sagaStatus");
        requireText(rawPayload, "raw command payload");
    }

    public void requireExactReplay(SagaCommandIdentity incoming) {
        if (!commandType.equals(incoming.commandType)
                || !orderId.equals(incoming.orderId)
                || !sagaStatus.equals(incoming.sagaStatus)
                || !fingerprint.equals(incoming.fingerprint)) {
            throw new IllegalArgumentException(
                    "saga order command eventId replay has contradictory command identity or payload");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
