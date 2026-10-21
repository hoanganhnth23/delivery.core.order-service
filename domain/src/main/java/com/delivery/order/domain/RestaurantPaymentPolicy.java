package com.delivery.order.domain;

import java.util.UUID;

/** Restaurant decisions and the existing, optional non-COD compatibility rail. */
public final class RestaurantPaymentPolicy {
    private RestaurantPaymentPolicy() { }

    public static void admitRestaurantDecision(UUID eventId, Long actorUserId,
            Long restaurantId, Long orderRestaurantId) {
        if (eventId == null) {
            throw new IllegalArgumentException("restaurant decision eventId is required");
        }
        if (actorUserId == null || actorUserId <= 0) {
            throw new IllegalArgumentException("restaurant decision actorUserId must be positive");
        }
        if (restaurantId == null || !restaurantId.equals(orderRestaurantId)) {
            throw new IllegalArgumentException("restaurantId trong event không khớp đơn hàng");
        }
    }

    /** Receipt fields are persisted non-null; comparisons preserve replay precedence. */
    public record Receipt(Long orderId, Long restaurantId, String decision, String fingerprint) {
        public void requireExactReplay(Receipt incoming) {
            if (!orderId.equals(incoming.orderId)
                    || !restaurantId.equals(incoming.restaurantId)
                    || !decision.equals(incoming.decision)
                    || !fingerprint.equals(incoming.fingerprint)) {
                throw new IllegalArgumentException(
                        "restaurant decision eventId replay has a contradictory payload");
            }
        }
    }

    public static void requireNoPreviousDecision(UUID previousEventId) {
        throw new IllegalStateException(
                "order already has a restaurant decision from event " + previousEventId);
    }

    /** Late confirmation records its receipt without regressing fulfilment. */
    public static boolean restaurantConfirmationChangesState(OrderStatus status) {
        return switch (status) {
            case CONFIRMED, FINDING_SHIPPER, WAIT_SHIPPER_CONFIRM, ASSIGNED,
                    PICKED_UP, DELIVERING, DELIVERED, SHIPPER_NOT_FOUND -> false;
            case PENDING -> true;
            case CANCELLED -> throw new IllegalStateException(
                    "Không thể xác nhận đơn ở trạng thái " + status);
        };
    }

    public static String restaurantRejectionReason(OrderStatus status, String reason) {
        if (status == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Restaurant rejection conflicts with existing cancellation");
        }
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Không thể từ chối đơn ở trạng thái " + status);
        }
        return "Rejected by restaurant: " + (reason != null ? reason : "Nhà hàng từ chối đơn");
    }

    public static boolean paymentCompletionChangesState(String paymentMethod, OrderStatus status) {
        return !"COD".equals(paymentMethod) && status == OrderStatus.PENDING;
    }

    public static boolean ignoresPaymentFailure(String paymentMethod) {
        return "COD".equals(paymentMethod);
    }

    public static String paymentFailureReason(String reason) {
        return reason == null ? "Payment failed" : reason;
    }
}
