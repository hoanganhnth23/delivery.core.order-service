package com.delivery.order_service.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    private final OrderStatusConverter converter = new OrderStatusConverter();

    @Test
    void legacyDatabaseValuesAreReadAsCanonicalStates() {
        assertEquals(OrderStatus.CONFIRMED,
                converter.convertToEntityAttribute("CONFIRMED_BY_RESTAURANT"));
        assertEquals(OrderStatus.ASSIGNED,
                converter.convertToEntityAttribute("ASSIGNED_TO_SHIPPER"));
        assertEquals(OrderStatus.DELIVERING,
                converter.convertToEntityAttribute("IN_DELIVERY"));
        assertEquals(OrderStatus.CANCELLED,
                converter.convertToEntityAttribute("REJECTED_BY_RESTAURANT"));
    }

    @Test
    void unknownExternalStatusRetainsHostEnumErrorMessage() {
        assertEquals("No enum constant com.delivery.order_service.entity.OrderStatus.UNKNOWN",
                assertThrows(IllegalArgumentException.class,
                        () -> OrderStatus.fromExternal(" unknown ")).getMessage());
        assertEquals("Order status is required", assertThrows(IllegalArgumentException.class,
                () -> OrderStatus.fromExternal(null)).getMessage());
    }

    @Test
    void converterWritesOnlyCanonicalNames() {
        assertEquals("WAIT_SHIPPER_CONFIRM",
                converter.convertToDatabaseColumn(OrderStatus.WAIT_SHIPPER_CONFIRM));
    }

    @Test
    void transitionTableAllowsHappyPathAndRejectsSkippedStates() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CONFIRMED));
        assertTrue(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.FINDING_SHIPPER));
        assertTrue(OrderStatus.FINDING_SHIPPER.canTransitionTo(OrderStatus.WAIT_SHIPPER_CONFIRM));
        assertTrue(OrderStatus.WAIT_SHIPPER_CONFIRM.canTransitionTo(OrderStatus.ASSIGNED));
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED));
        assertThrows(IllegalStateException.class,
                () -> OrderStatus.DELIVERED.requireTransitionTo(OrderStatus.CANCELLED));
    }
}
