package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RestaurantPaymentPolicyTest {
    @Test
    void everyRestaurantStatePreservesConfirmationConvergenceAndRejectionEligibility() {
        assertThrows(NullPointerException.class, () ->
                RestaurantPaymentPolicy.restaurantConfirmationChangesState(null));
        assertEquals("Không thể từ chối đơn ở trạng thái null", assertThrows(IllegalStateException.class,
                () -> RestaurantPaymentPolicy.restaurantRejectionReason(null, null)).getMessage());
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.CANCELLED) {
                assertEquals("Không thể xác nhận đơn ở trạng thái CANCELLED",
                        assertThrows(IllegalStateException.class, () ->
                                RestaurantPaymentPolicy.restaurantConfirmationChangesState(status)).getMessage());
            } else {
                assertEquals(status == OrderStatus.PENDING,
                        RestaurantPaymentPolicy.restaurantConfirmationChangesState(status));
            }
            if (status == OrderStatus.PENDING) {
                assertEquals("Rejected by restaurant: Nhà hàng từ chối đơn",
                        RestaurantPaymentPolicy.restaurantRejectionReason(status, null));
                assertEquals("Rejected by restaurant: ",
                        RestaurantPaymentPolicy.restaurantRejectionReason(status, ""));
                assertEquals("Rejected by restaurant: closed",
                        RestaurantPaymentPolicy.restaurantRejectionReason(status, "closed"));
            } else {
                assertEquals(status == OrderStatus.CANCELLED
                                ? "Restaurant rejection conflicts with existing cancellation"
                                : "Không thể từ chối đơn ở trạng thái " + status,
                        assertThrows(IllegalStateException.class, () ->
                                RestaurantPaymentPolicy.restaurantRejectionReason(status, "closed")).getMessage());
            }
        }
    }

    @Test
    void paymentCompatibilityIncludesNullUnknownAndCaseSensitiveMethodsInEveryState() {
        for (String method : new String[]{"COD", "ONLINE", "cod", "", null}) {
            for (OrderStatus status : OrderStatus.values()) {
                assertEquals(!"COD".equals(method) && status == OrderStatus.PENDING,
                        RestaurantPaymentPolicy.paymentCompletionChangesState(method, status));
                assertEquals("COD".equals(method), RestaurantPaymentPolicy.ignoresPaymentFailure(method));
                // Failure still uses the shared transition table, including CANCELLED self-transition.
                if (!"COD".equals(method)) {
                    if (status.canTransitionTo(OrderStatus.CANCELLED)) {
                        assertDoesNotThrow(() -> status.requireTransitionTo(OrderStatus.CANCELLED));
                    } else {
                        assertThrows(IllegalStateException.class, () -> status.requireTransitionTo(OrderStatus.CANCELLED));
                    }
                }
            }
        }
        assertFalse(RestaurantPaymentPolicy.paymentCompletionChangesState("ONLINE", null));
        assertFalse(RestaurantPaymentPolicy.paymentCompletionChangesState("COD", null));
        assertEquals("Payment failed", RestaurantPaymentPolicy.paymentFailureReason(null));
        assertEquals("", RestaurantPaymentPolicy.paymentFailureReason(""));
        assertEquals("declined", RestaurantPaymentPolicy.paymentFailureReason("declined"));
    }

    @Test
    void restaurantAdmissionRetainsValidationPrecedenceAndMessages() {
        UUID id = UUID.randomUUID();
        assertEquals("restaurant decision eventId is required", assertThrows(IllegalArgumentException.class,
                () -> RestaurantPaymentPolicy.admitRestaurantDecision(null, null, null, 8L)).getMessage());
        for (Long actor : new Long[]{null, 0L, -1L}) {
            assertEquals("restaurant decision actorUserId must be positive", assertThrows(IllegalArgumentException.class,
                    () -> RestaurantPaymentPolicy.admitRestaurantDecision(id, actor, null, 8L)).getMessage());
        }
        for (Long restaurant : new Long[]{null, 9L}) {
            assertEquals("restaurantId trong event không khớp đơn hàng", assertThrows(IllegalArgumentException.class,
                    () -> RestaurantPaymentPolicy.admitRestaurantDecision(id, 7L, restaurant, 8L)).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () ->
                RestaurantPaymentPolicy.admitRestaurantDecision(id, 7L, 8L, null));
        assertDoesNotThrow(() -> RestaurantPaymentPolicy.admitRestaurantDecision(id, 7L, 8L, 8L));
        // No new positivity constraint is introduced for matching restaurant identities.
        assertDoesNotThrow(() -> RestaurantPaymentPolicy.admitRestaurantDecision(id, 7L, 0L, 0L));
    }

    @Test
    void receiptReplayBindsEveryPersistedFieldAndRejectsAnotherEventForTheOrder() {
        var receipt = new RestaurantPaymentPolicy.Receipt(1L, 8L, "CONFIRMED", "hash");
        assertDoesNotThrow(() -> receipt.requireExactReplay(receipt));
        for (var changed : new RestaurantPaymentPolicy.Receipt[]{
                new RestaurantPaymentPolicy.Receipt(2L, 8L, "CONFIRMED", "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, 9L, "CONFIRMED", "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, 8L, "REJECTED", "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, 8L, "CONFIRMED", "other"),
                new RestaurantPaymentPolicy.Receipt(null, 8L, "CONFIRMED", "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, null, "CONFIRMED", "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, 8L, null, "hash"),
                new RestaurantPaymentPolicy.Receipt(1L, 8L, "CONFIRMED", null)}) {
            assertEquals("restaurant decision eventId replay has a contradictory payload",
                    assertThrows(IllegalArgumentException.class, () -> receipt.requireExactReplay(changed)).getMessage());
        }
        UUID previous = UUID.randomUUID();
        assertEquals("order already has a restaurant decision from event " + previous,
                assertThrows(IllegalStateException.class, () ->
                        RestaurantPaymentPolicy.requireNoPreviousDecision(previous)).getMessage());
    }
}
