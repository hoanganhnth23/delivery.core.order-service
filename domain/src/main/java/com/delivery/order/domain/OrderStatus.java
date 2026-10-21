package com.delivery.order.domain;

import java.util.Locale;
import java.util.Set;

/** Canonical public and persistence vocabulary for the COD order lifecycle. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FINDING_SHIPPER,
    WAIT_SHIPPER_CONFIRM,
    ASSIGNED,
    PICKED_UP,
    DELIVERING,
    DELIVERED,
    CANCELLED,
    SHIPPER_NOT_FOUND;

    public static OrderStatus fromExternal(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Order status is required");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CONFIRMED_BY_RESTAURANT", "READY" -> CONFIRMED;
            case "ASSIGNED_TO_SHIPPER" -> ASSIGNED;
            case "IN_DELIVERY", "IN_PROGRESS" -> DELIVERING;
            case "REJECTED_BY_RESTAURANT", "PAYMENT_FAILED" -> CANCELLED;
            case "PENDING_PAYMENT" -> PENDING;
            default -> OrderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == SHIPPER_NOT_FOUND;
    }

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null || target == this) {
            return target == this;
        }
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, CANCELLED).contains(target);
            case CONFIRMED -> Set.of(FINDING_SHIPPER, CANCELLED, SHIPPER_NOT_FOUND).contains(target);
            case FINDING_SHIPPER -> Set.of(WAIT_SHIPPER_CONFIRM, ASSIGNED, CANCELLED, SHIPPER_NOT_FOUND).contains(target);
            case WAIT_SHIPPER_CONFIRM -> Set.of(ASSIGNED, FINDING_SHIPPER, CANCELLED, SHIPPER_NOT_FOUND).contains(target);
            case ASSIGNED -> Set.of(PICKED_UP, FINDING_SHIPPER, CANCELLED).contains(target);
            case PICKED_UP -> target == DELIVERING;
            case DELIVERING -> target == DELIVERED;
            case DELIVERED, CANCELLED, SHIPPER_NOT_FOUND -> false;
        };
    }

    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Invalid order transition: " + this + " -> " + target);
        }
    }
}
