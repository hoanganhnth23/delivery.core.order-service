package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.order.domain.SagaStatusPolicy.SequenceDecision.*;

class SagaStatusPolicyTest {
    @Test
    void legacyStaleNextAndGapBoundariesPreserveRollingCompatibility() {
        for (long incoming : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertEquals(LEGACY, SagaStatusPolicy.sequence(0, incoming));
            for (long cursor : new long[]{-1, 1, Long.MAX_VALUE}) {
                assertEquals("Legacy Saga command arrived after sequenced commands",
                        assertThrows(IllegalArgumentException.class,
                                () -> SagaStatusPolicy.sequence(cursor, incoming)).getMessage());
            }
        }
        assertEquals(NEXT, SagaStatusPolicy.sequence(0, 1));
        assertEquals(GAP, SagaStatusPolicy.sequence(0, 2));
        assertEquals(STALE, SagaStatusPolicy.sequence(4, 1));
        assertEquals(STALE, SagaStatusPolicy.sequence(4, 4));
        assertEquals(NEXT, SagaStatusPolicy.sequence(4, 5));
        assertEquals(GAP, SagaStatusPolicy.sequence(4, 6));
        assertEquals(NEXT, SagaStatusPolicy.sequence(Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertEquals(STALE, SagaStatusPolicy.sequence(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(GAP, SagaStatusPolicy.sequence(-1, 1));
    }

    @Test
    void deliveryMappingIsCaseSensitiveAndHasNoAdditionalAliases() {
        Map<String, OrderStatus> mapping = Map.of(
                "ASSIGNED", OrderStatus.ASSIGNED, "WAIT_SHIPPER_CONFIRM", OrderStatus.WAIT_SHIPPER_CONFIRM,
                "FINDING_SHIPPER", OrderStatus.FINDING_SHIPPER, "IN_PROGRESS", OrderStatus.DELIVERING,
                "PICKED_UP", OrderStatus.PICKED_UP, "DELIVERING", OrderStatus.DELIVERING,
                "DELIVERED", OrderStatus.DELIVERED, "CANCELLED", OrderStatus.CANCELLED,
                "SHIPPER_NOT_FOUND", OrderStatus.SHIPPER_NOT_FOUND);
        mapping.forEach((value, target) -> assertEquals(target, SagaStatusPolicy.deliveryStatus(value)));
        assertEquals("Delivery status is required", assertThrows(IllegalArgumentException.class,
                () -> SagaStatusPolicy.deliveryStatus(null)).getMessage());
        for (String value : new String[]{"", "assigned", " ASSIGNED", "PENDING", "CONFIRMED", "SHIPPER_FOUND"}) {
            assertEquals("Unknown delivery status: " + value, assertThrows(IllegalArgumentException.class,
                    () -> SagaStatusPolicy.deliveryStatus(value)).getMessage());
        }
    }

    @Test
    void everyDeliveryUpdateIsNoOpValidPathBridgeOrOriginalInvalidTransition() {
        for (OrderStatus source : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                if (source == target) assertEquals(List.of(), SagaStatusPolicy.deliveryTransitions(source, target));
                else if (source == OrderStatus.PENDING && target == OrderStatus.FINDING_SHIPPER) {
                    assertEquals(List.of(OrderStatus.CONFIRMED, OrderStatus.FINDING_SHIPPER), SagaStatusPolicy.deliveryTransitions(source, target));
                } else if (source.canTransitionTo(target)) {
                    assertEquals(List.of(target), SagaStatusPolicy.deliveryTransitions(source, target));
                } else {
                    assertEquals("Invalid order transition: " + source + " -> " + target,
                            assertThrows(IllegalStateException.class,
                                    () -> SagaStatusPolicy.deliveryTransitions(source, target)).getMessage());
                }
            }
        }
    }

    @Test
    void everyAssignmentAndNoShipperStateRetainsReplayAndEligibilityRules() {
        Set<OrderStatus> assigned = Set.of(OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, OrderStatus.DELIVERING, OrderStatus.DELIVERED);
        Set<OrderStatus> matching = Set.of(OrderStatus.CONFIRMED, OrderStatus.FINDING_SHIPPER, OrderStatus.WAIT_SHIPPER_CONFIRM);
        for (OrderStatus status : OrderStatus.values()) {
            assertEquals(matching.contains(status), SagaStatusPolicy.appliesShipperNotFound(status));
            assertEquals(assigned.contains(status), SagaStatusPolicy.assignmentReplay(status, 8L, 8L));
            for (Long stored : new Long[]{null, 9L}) {
                if (assigned.contains(status)) {
                    assertEquals("Shipper acceptance conflicts with assigned shipper",
                            assertThrows(IllegalStateException.class,
                                    () -> SagaStatusPolicy.assignmentReplay(status, stored, 8L)).getMessage());
                } else assertFalse(SagaStatusPolicy.assignmentReplay(status, stored, 8L));
            }
            for (Long incoming : new Long[]{null, 0L, -1L}) {
                assertEquals("shipperId must be positive", assertThrows(IllegalArgumentException.class,
                        () -> SagaStatusPolicy.assignmentReplay(status, 8L, incoming)).getMessage());
            }
        }
    }
}
