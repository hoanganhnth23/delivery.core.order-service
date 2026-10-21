package com.delivery.order.domain;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CheckoutRecoveryPolicyTest {
    @Test void fingerprintAndVersionBothBindTheReceiptWithOriginalNullSemantics() {
        for (String stored : Arrays.asList(null, "hash", "different")) {
            for (String version : Arrays.asList(null, "v1", "different")) {
                assertEquals("hash".equals(stored) && "v1".equals(version),
                        IdempotencyLeasePolicy.fingerprintMatches("hash", stored, "v1", version));
            }
        }
        // The legacy claim compares from the stored fingerprint; null still fails closed.
        assertThrows(NullPointerException.class,
                () -> IdempotencyLeasePolicy.fingerprintMatches(null, "hash", "v1", "v1"));
    }
    @Test void leaseBoundariesAndExactExpiry() {
        assertEquals(Duration.ofSeconds(30), IdempotencyLeasePolicy.boundedLease(null));
        for (int seconds : new int[]{-1, 4, 5, 30, 300, 301})
            assertEquals(Duration.ofSeconds(Math.max(5, Math.min(300, seconds))),
                    IdempotencyLeasePolicy.boundedLease(Duration.ofSeconds(seconds)));
        Instant now = Instant.EPOCH;
        assertFalse(IdempotencyLeasePolicy.live(null, now));
        assertFalse(IdempotencyLeasePolicy.live(now, now));
        assertFalse(IdempotencyLeasePolicy.live(now.minusNanos(1), now));
        assertTrue(IdempotencyLeasePolicy.live(now.plusNanos(1), now));
        UUID token = UUID.randomUUID();
        assertTrue(IdempotencyLeasePolicy.owned(token, token));
        assertFalse(IdempotencyLeasePolicy.owned(token, null));
        assertFalse(IdempotencyLeasePolicy.owned(token, UUID.randomUUID()));
    }
    @Test void voucherValidationAndEveryRail() {
        assertEquals(List.of(), CheckoutReservationPolicy.selectedIds(null));
        List<Long> source = new ArrayList<>(List.of(1L, 2L, 3L));
        assertNotSame(source, CheckoutReservationPolicy.selectedIds(source));
        for (List<Long> invalid : List.of(List.of(1L,2L,3L,4L), List.of(1L,1L), List.of(0L), List.of(-1L), Arrays.asList((Long)null)))
            assertThrows(IllegalArgumentException.class, () -> CheckoutReservationPolicy.selectedIds(invalid));
        for (String mode : Arrays.asList(null, "AUTO", "auto", "MANUAL", "manual", "OTHER")) {
            for (List<Long> ids : List.of(List.<Long>of(), List.of(1L), List.of(1L,2L))) {
                assertEquals("AUTO".equalsIgnoreCase(mode) && ids.isEmpty(), CheckoutReservationPolicy.needsAuto(mode, ids));
                if ("MANUAL".equalsIgnoreCase(mode) && ids.isEmpty()) {
                    assertThrows(IllegalArgumentException.class, () -> CheckoutReservationPolicy.rail(mode, ids));
                } else {
                    var expected = ids.isEmpty() ? CheckoutReservationPolicy.Rail.NONE
                            : ids.size() == 1 && mode == null ? CheckoutReservationPolicy.Rail.LEGACY
                            : CheckoutReservationPolicy.Rail.PROMOTION;
                    assertEquals(expected, CheckoutReservationPolicy.rail(mode, ids));
                }
            }
        }
    }
}
