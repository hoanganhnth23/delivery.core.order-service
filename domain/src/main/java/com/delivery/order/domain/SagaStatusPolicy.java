package com.delivery.order.domain;

import java.util.List;

/** Decisions for Saga status commands; locking, receipts and effects belong to the host. */
public final class SagaStatusPolicy {
    private SagaStatusPolicy() { }

    public enum SequenceDecision { LEGACY, STALE, NEXT, GAP }

    public static SequenceDecision sequence(long cursor, long incoming) {
        if (incoming <= 0) {
            if (cursor != 0) {
                throw new IllegalArgumentException("Legacy Saga command arrived after sequenced commands");
            }
            return SequenceDecision.LEGACY;
        }
        if (incoming <= cursor) return SequenceDecision.STALE;
        return incoming == cursor + 1 ? SequenceDecision.NEXT : SequenceDecision.GAP;
    }

    public static OrderStatus deliveryStatus(String value) {
        if (value == null) throw new IllegalArgumentException("Delivery status is required");
        return switch (value) {
            case "ASSIGNED" -> OrderStatus.ASSIGNED;
            case "WAIT_SHIPPER_CONFIRM" -> OrderStatus.WAIT_SHIPPER_CONFIRM;
            case "FINDING_SHIPPER" -> OrderStatus.FINDING_SHIPPER;
            case "IN_PROGRESS", "DELIVERING" -> OrderStatus.DELIVERING;
            case "PICKED_UP" -> OrderStatus.PICKED_UP;
            case "DELIVERED" -> OrderStatus.DELIVERED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "SHIPPER_NOT_FOUND" -> OrderStatus.SHIPPER_NOT_FOUND;
            default -> throw new IllegalArgumentException("Unknown delivery status: " + value);
        };
    }

    /** Same-state updates have no effects; confirmation can be overtaken across topics. */
    public static List<OrderStatus> deliveryTransitions(OrderStatus current, OrderStatus target) {
        if (current == target) return List.of();
        if (current == OrderStatus.PENDING && target == OrderStatus.FINDING_SHIPPER) {
            return List.of(OrderStatus.CONFIRMED, OrderStatus.FINDING_SHIPPER);
        }
        current.requireTransitionTo(target);
        return List.of(target);
    }

    /** @return true for an already-applied assignment, false for a new assignment. */
    public static boolean assignmentReplay(OrderStatus current, Long assignedShipper, Long incomingShipper) {
        if (incomingShipper == null || incomingShipper <= 0) {
            throw new IllegalArgumentException("shipperId must be positive");
        }
        if (current == OrderStatus.ASSIGNED || current == OrderStatus.PICKED_UP
                || current == OrderStatus.DELIVERING || current == OrderStatus.DELIVERED) {
            if (incomingShipper.equals(assignedShipper)) return true;
            throw new IllegalStateException("Shipper acceptance conflicts with assigned shipper");
        }
        return false;
    }

    public static boolean appliesShipperNotFound(OrderStatus current) {
        return current == OrderStatus.CONFIRMED || current == OrderStatus.FINDING_SHIPPER
                || current == OrderStatus.WAIT_SHIPPER_CONFIRM;
    }
}
