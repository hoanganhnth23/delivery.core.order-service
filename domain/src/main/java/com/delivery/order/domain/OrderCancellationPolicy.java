package com.delivery.order.domain;

import java.util.Objects;

/** Cancellation admission/replay and typed compensation intent, not provider refund eligibility. */
public final class OrderCancellationPolicy {
    private OrderCancellationPolicy() { }

    public static boolean requireExactReplay(Long cancelledByPrincipalId, Long cancelledBy,
            String existingReason, Long principalId, Long legacyUserId, String reason, boolean enforced) {
        if ((cancelledByPrincipalId != null && Objects.equals(cancelledByPrincipalId, principalId)
                || !enforced && cancelledByPrincipalId == null && Objects.equals(cancelledBy, legacyUserId))
                && Objects.equals(existingReason, reason)) {
            return cancelledByPrincipalId == null;
        }
        throw new IllegalStateException("Order cancellation already exists with a different actor or reason");
    }

    /** ADMIN bypasses this guard only; the host still requires the canonical transition. */
    public static void requireCancellable(OrderStatus status, String role) {
        if ("ADMIN".equals(role)) return;
        if (status != OrderStatus.PENDING && status != OrderStatus.CONFIRMED
                && status != OrderStatus.FINDING_SHIPPER && status != OrderStatus.WAIT_SHIPPER_CONFIRM
                && status != OrderStatus.ASSIGNED) {
            throw new IllegalStateException("Không thể hủy đơn hàng ở trạng thái: " + status);
        }
    }

    public record Intent(String currentStatus, String source, String reasonCode) { }

    public static Intent actorIntent(String role) {
        return "ADMIN".equals(role) ? new Intent("CANCELLED", "ADMIN", "ADMIN_CANCELLED")
                : "SHOP_OWNER".equals(role) ? new Intent("CANCELLED", "RESTAURANT", "RESTAURANT_CANCELLED")
                : new Intent("CANCELLED", "CUSTOMER", "CUSTOMER_CANCELLED");
    }

    /** The food went back to the restaurant after a failed delivery: the order ends as cancelled. */
    public enum FailedDelivery { APPLY, ALREADY_APPLIED, IGNORE, NOT_YET }

    /**
     * Delivery owns the outcome and reports it directly, while Order learns
     * pick-up/delivering through Dispatch, so Order's own status may lag behind:
     * ASSIGNED is applied, earlier statuses wait (NOT_YET -> retried), and an
     * order that already ended differently is left alone.
     */
    public static FailedDelivery onDeliveryReturned(OrderStatus status) {
        if (status == null) return FailedDelivery.IGNORE;
        return switch (status) {
            case CANCELLED -> FailedDelivery.ALREADY_APPLIED;
            case ASSIGNED, PICKED_UP, DELIVERING -> FailedDelivery.APPLY;
            case PENDING, CONFIRMED, FINDING_SHIPPER, WAIT_SHIPPER_CONFIRM -> FailedDelivery.NOT_YET;
            case DELIVERED, SHIPPER_NOT_FOUND -> FailedDelivery.IGNORE;
        };
    }

    public static String failedDeliveryReason(String deliveryReason) {
        String base = "Giao hàng thất bại, đồ ăn đã được trả về quán";
        return deliveryReason == null || deliveryReason.isBlank() ? base : base + ": " + deliveryReason.trim();
    }

    public static Intent noShipperIntent() {
        return new Intent("SHIPPER_NOT_FOUND", "SYSTEM", "SHIPPER_NOT_FOUND");
    }

    public static String noShipperReason(String reason) {
        return reason == null || reason.isBlank() ? "No shipper available" : reason;
    }
}
