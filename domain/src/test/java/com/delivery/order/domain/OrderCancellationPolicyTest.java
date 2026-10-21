package com.delivery.order.domain;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.order.domain.OrderCancellationPolicy.*;

class OrderCancellationPolicyTest {
    @Test
    void everyStateRetainsAdminGuardBypassButTransitionTableRestriction() {
        for (OrderStatus status : OrderStatus.values()) {
            boolean prePickup = switch (status) {
                case PENDING, CONFIRMED, FINDING_SHIPPER, WAIT_SHIPPER_CONFIRM, ASSIGNED -> true;
                default -> false;
            };
            assertDoesNotThrow(() -> requireCancellable(status, "ADMIN"));
            for (String role : new String[]{"USER", "SHOP_OWNER", null}) {
                if (prePickup) assertDoesNotThrow(() -> requireCancellable(status, role));
                else assertEquals("Không thể hủy đơn hàng ở trạng thái: " + status,
                        assertThrows(IllegalStateException.class, () -> requireCancellable(status, role)).getMessage());
            }
            if (!prePickup && status != OrderStatus.CANCELLED) {
                assertEquals("Invalid order transition: " + status + " -> CANCELLED",
                        assertThrows(IllegalStateException.class, () -> status.requireTransitionTo(OrderStatus.CANCELLED)).getMessage());
            }
        }
        assertDoesNotThrow(() -> requireCancellable(null, "ADMIN"));
        assertEquals("Không thể hủy đơn hàng ở trạng thái: null",
                assertThrows(IllegalStateException.class, () -> requireCancellable(null, "USER")).getMessage());
    }

    @Test
    void exhaustiveReplayIdentityReasonAndEnforcementMatrix() {
        Long[] ids = {null, 1L, 2L};
        String[] reasons = {null, "", "reason", "different"};
        for (Long storedPrincipal : ids) for (Long storedLegacy : ids) for (Long principal : ids) for (Long legacy : ids) {
            for (String storedReason : reasons) for (String reason : reasons) for (boolean enforced : new boolean[]{false, true}) {
                boolean actor = storedPrincipal == null ? !enforced && Objects.equals(storedLegacy, legacy)
                        : storedPrincipal.equals(principal);
                if (actor && Objects.equals(storedReason, reason)) {
                    assertEquals(storedPrincipal == null, requireExactReplay(storedPrincipal, storedLegacy, storedReason,
                            principal, legacy, reason, enforced));
                } else {
                    assertEquals("Order cancellation already exists with a different actor or reason",
                            assertThrows(IllegalStateException.class, () -> requireExactReplay(storedPrincipal, storedLegacy,
                                    storedReason, principal, legacy, reason, enforced)).getMessage());
                }
            }
        }
    }

    @Test
    void typedIntentAndNoShipperReasonDoNotDecideProviderRefunds() {
        assertEquals(new Intent("CANCELLED", "ADMIN", "ADMIN_CANCELLED"), actorIntent("ADMIN"));
        assertEquals(new Intent("CANCELLED", "RESTAURANT", "RESTAURANT_CANCELLED"), actorIntent("SHOP_OWNER"));
        for (String role : new String[]{"USER", null, "OTHER"}) {
            assertEquals(new Intent("CANCELLED", "CUSTOMER", "CUSTOMER_CANCELLED"), actorIntent(role));
        }
        assertEquals(new Intent("SHIPPER_NOT_FOUND", "SYSTEM", "SHIPPER_NOT_FOUND"), noShipperIntent());
        for (String reason : new String[]{null, "", " \t"}) assertEquals("No shipper available", noShipperReason(reason));
        assertEquals(" reason ", noShipperReason(" reason "));
    }
}
