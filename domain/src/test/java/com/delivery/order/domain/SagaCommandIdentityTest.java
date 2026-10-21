package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SagaCommandIdentityTest {
    private final UUID eventId = UUID.randomUUID();

    @Test
    void admissionPreservesValidationOrderAndMessages() {
        assertEquals("eventId is required", assertThrows(IllegalArgumentException.class,
                () -> SagaCommandIdentity.requireCommand(null, null, null, null, null)).getMessage());
        for (String text : new String[]{null, "", " "}) {
            assertEquals("commandType is required", assertThrows(IllegalArgumentException.class,
                    () -> SagaCommandIdentity.requireCommand(eventId, text, null, null, null)).getMessage());
            assertEquals("sagaStatus is required", assertThrows(IllegalArgumentException.class,
                    () -> SagaCommandIdentity.requireCommand(eventId, "UPDATE_ORDER_STATUS", 1L, text, null)).getMessage());
            assertEquals("raw command payload is required", assertThrows(IllegalArgumentException.class,
                    () -> SagaCommandIdentity.requireCommand(eventId, "UPDATE_ORDER_STATUS", 1L, "DELIVERED", text)).getMessage());
        }
        for (Long orderId : new Long[]{null, 0L, -1L}) {
            assertEquals("orderId must be positive", assertThrows(IllegalArgumentException.class,
                    () -> SagaCommandIdentity.requireCommand(eventId, "UPDATE_ORDER_STATUS", orderId, null, null)).getMessage());
        }
        assertDoesNotThrow(() -> SagaCommandIdentity.requireCommand(eventId, "UPDATE_ORDER_STATUS", 1L, "DELIVERED", " {} "));
    }

    @Test
    void eachStoredFieldIndependentlyFencesContradictoryReplay() {
        SagaCommandIdentity stored = new SagaCommandIdentity("UPDATE_ORDER_STATUS", 1L, "DELIVERED", "raw-hash");
        assertDoesNotThrow(() -> stored.requireExactReplay(new SagaCommandIdentity("UPDATE_ORDER_STATUS", 1L, "DELIVERED", "raw-hash")));
        for (SagaCommandIdentity different : new SagaCommandIdentity[]{
                new SagaCommandIdentity("OTHER", 1L, "DELIVERED", "raw-hash"),
                new SagaCommandIdentity("UPDATE_ORDER_STATUS", 2L, "DELIVERED", "raw-hash"),
                new SagaCommandIdentity("UPDATE_ORDER_STATUS", 1L, "CANCELLED", "raw-hash"),
                new SagaCommandIdentity("UPDATE_ORDER_STATUS", 1L, "DELIVERED", "other-hash")}) {
            assertEquals("saga order command eventId replay has contradictory command identity or payload",
                    assertThrows(IllegalArgumentException.class, () -> stored.requireExactReplay(different)).getMessage());
        }
    }
}
