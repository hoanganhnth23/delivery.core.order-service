package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {
    private static final Map<OrderStatus, Set<OrderStatus>> EDGES = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.FINDING_SHIPPER, OrderStatus.CANCELLED, OrderStatus.SHIPPER_NOT_FOUND),
            OrderStatus.FINDING_SHIPPER, Set.of(OrderStatus.WAIT_SHIPPER_CONFIRM, OrderStatus.ASSIGNED, OrderStatus.CANCELLED, OrderStatus.SHIPPER_NOT_FOUND),
            OrderStatus.WAIT_SHIPPER_CONFIRM, Set.of(OrderStatus.ASSIGNED, OrderStatus.FINDING_SHIPPER, OrderStatus.CANCELLED, OrderStatus.SHIPPER_NOT_FOUND),
            OrderStatus.ASSIGNED, Set.of(OrderStatus.PICKED_UP, OrderStatus.FINDING_SHIPPER, OrderStatus.CANCELLED),
            OrderStatus.PICKED_UP, Set.of(OrderStatus.DELIVERING),
            OrderStatus.DELIVERING, Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(), OrderStatus.CANCELLED, Set.of(), OrderStatus.SHIPPER_NOT_FOUND, Set.of());

    @Test
    void everyTransitionIncludingSelfAndNullHasTheOriginalOutcome() {
        for (OrderStatus source : OrderStatus.values()) {
            assertFalse(source.canTransitionTo(null));
            assertEquals("Invalid order transition: " + source + " -> null",
                    assertThrows(IllegalStateException.class, () -> source.requireTransitionTo(null)).getMessage());
            assertEquals(Set.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.SHIPPER_NOT_FOUND).contains(source), source.isTerminal());
            for (OrderStatus target : OrderStatus.values()) {
                boolean allowed = source == target || EDGES.get(source).contains(target);
                assertEquals(allowed, source.canTransitionTo(target), source + " -> " + target);
                if (allowed) assertDoesNotThrow(() -> source.requireTransitionTo(target));
                else assertEquals("Invalid order transition: " + source + " -> " + target,
                        assertThrows(IllegalStateException.class, () -> source.requireTransitionTo(target)).getMessage());
            }
        }
    }

    @Test
    void canonicalAndLegacyExternalVocabularyAndFailuresAreUnchanged() {
        for (OrderStatus status : OrderStatus.values()) {
            assertEquals(status, OrderStatus.fromExternal(" " + status.name().toLowerCase(java.util.Locale.ROOT) + " "));
        }
        Map<String, OrderStatus> aliases = Map.of(
                "CONFIRMED_BY_RESTAURANT", OrderStatus.CONFIRMED, "READY", OrderStatus.CONFIRMED,
                "ASSIGNED_TO_SHIPPER", OrderStatus.ASSIGNED, "IN_DELIVERY", OrderStatus.DELIVERING,
                "IN_PROGRESS", OrderStatus.DELIVERING, "REJECTED_BY_RESTAURANT", OrderStatus.CANCELLED,
                "PAYMENT_FAILED", OrderStatus.CANCELLED, "PENDING_PAYMENT", OrderStatus.PENDING);
        aliases.forEach((text, expected) -> assertEquals(expected, OrderStatus.fromExternal(" " + text.toLowerCase(java.util.Locale.ROOT) + " ")));
        for (String value : new String[]{null, "", " "}) {
            assertEquals("Order status is required", assertThrows(IllegalArgumentException.class,
                    () -> OrderStatus.fromExternal(value)).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromExternal("unknown"));
    }
}
