package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ShippingPolicyTest {
    @Test void baseExcessCeilingHalfUpAndCapAreAppliedInThatOrder() {
        assertEquals(new BigDecimal("12000"),ShippingPolicy.fee(0));
        assertEquals(new BigDecimal("12000"),ShippingPolicy.fee(2));
        // Preserve double subtraction: the nominal 249 boundary drifts slightly upward.
        assertEquals(new BigDecimal("12000"),ShippingPolicy.fee(Math.nextDown(2 + 249.0/4500)));
        assertEquals(new BigDecimal("12500"),ShippingPolicy.fee(2 + 249.0/4500));
        assertEquals(new BigDecimal("12500"),ShippingPolicy.fee(2 + 249.001/4500));
        assertEquals(new BigDecimal("12500"),ShippingPolicy.fee(2 + 250.0/4500));
        assertEquals(new BigDecimal("16500"),ShippingPolicy.fee(3));
        assertEquals(new BigDecimal("25500"),ShippingPolicy.fee(5));
        assertEquals(new BigDecimal("49500"),ShippingPolicy.fee(2 + 37499.0/4500));
        assertEquals(new BigDecimal("50000"),ShippingPolicy.fee(2 + 37749.001/4500));
        assertEquals(new BigDecimal("50000"),ShippingPolicy.fee(2 + 38000.0/4500));
        assertEquals(new BigDecimal("50000"),ShippingPolicy.fee(1000));
    }

    @Test void eachCoordinateRejectsNullNonfiniteAndBounds() {
        assertTrue(ShippingPolicy.validCoordinates(8.0,102.0,24.0,110.0));
        for (int position = 0; position < 4; position++) {
            double min = position % 2 == 0 ? 8 : 102, max = position % 2 == 0 ? 24 : 110;
            for (Double invalid : Arrays.asList(null,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,min-0.001,max+0.001)) {
                Double[] coords = {10.0,106.0,11.0,107.0}; coords[position] = invalid;
                assertFalse(ShippingPolicy.validCoordinates(coords[0],coords[1],coords[2],coords[3]));
            }
        }
    }

    @Test void haversineRetainsZeroSymmetryAndKnownDistance() {
        assertEquals(0,ShippingPolicy.distance(10.0,106.0,10.0,106.0));
        assertEquals(111.19492664455873,ShippingPolicy.distance(10.0,106.0,11.0,106.0),1e-9);
        assertEquals(ShippingPolicy.distance(10.0,106.0,11.0,107.0),ShippingPolicy.distance(11.0,107.0,10.0,106.0));
    }
}
