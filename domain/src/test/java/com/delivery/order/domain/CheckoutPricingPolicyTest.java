package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.order.domain.CheckoutPricingPolicy.*;

class CheckoutPricingPolicyTest {
    private BigDecimal money(String value) { return new BigDecimal(value); }

    @Test void canonicalAndLivestreamFactsPreserveScaleAndEagerRegularLookup() {
        AtomicInteger lookups = new AtomicInteger();
        MenuPricePort menu = id -> { lookups.incrementAndGet(); return money("12.34567"); };
        assertEquals(money("12.34567"), regularOrLivestream(1L, menu, Map.of()));
        assertEquals(money("1.2345"), regularOrLivestream(1L, menu, Map.of(1L,money("1.2345"))));
        assertEquals(2, lookups.get());
        assertThrows(IllegalStateException.class, () -> regularOrLivestream(1L,
                id -> { throw new IllegalStateException("missing regular fact"); }, Map.of(1L,BigDecimal.ONE)));
        assertEquals(money("37.03701"), lineTotal(money("12.34567"),3));
        assertEquals(money("38.27151"), subtotal(Stream.of(new Line(money("12.34567"),3),new Line(money("1.2345"),1))));
        assertEquals(BigDecimal.ZERO, subtotal(Stream.empty()));
        // Interleaved arithmetic matters: a broken first line must fail before a later lookup.
        assertThrows(NullPointerException.class, () -> subtotal(Stream.of(1,2).map(i -> {
            if (i == 2) fail("must not resolve later facts after a failed first line");
            return new Line(null,1);
        })));
    }

    @Test void flashBindingCoversEachIdentityAndQuantityMismatch() {
        assertFalse(flashMatches(1L,2,null));
        assertFalse(flashMatches(1L,2,new FlashPrice(3L,2,money("5.123"))));
        assertFalse(flashMatches(1L,2,new FlashPrice(1L,3,money("5.123"))));
        assertTrue(flashMatches(1L,2,new FlashPrice(1L,2,money("5.123"))));
    }

    @Test void discountsAndPayableThresholdKeepExactComparisonAndLegacyFallback() {
        BigDecimal subtotal = money("100.12345"), shipping = money("12.000");
        for (String d : List.of("0","0.000","0.00001","112.12345")) assertTrue(validDiscount(money(d), subtotal, shipping));
        for (String d : List.of("-0.00001","112.12346")) assertFalse(validDiscount(money(d), subtotal, shipping));
        assertEquals(money("10.8766"),customerShipping(shipping,money("1.1234"),null));
        assertEquals(BigDecimal.ZERO,customerShipping(shipping,money("20"),null));
        assertEquals(money("-1.123"),customerShipping(shipping,money("20"),money("-1.123")));
        assertEquals(money("102.00005"),total(subtotal,money("10.1234"),shipping));
        assertFalse(positivePayableFood(money("11.9999"), shipping));
        assertFalse(positivePayableFood(money("12"), shipping));
        assertTrue(positivePayableFood(money("12.00001"), shipping));
    }

    @Test void canonicalItemAndRestaurantAdmissionCoverMissingAndNonfiniteFacts() {
        for (String name : Arrays.asList(null,""," ","Food"))
            for (BigDecimal price : Arrays.asList(null,money("-1"),BigDecimal.ZERO,money("0.00001")))
                assertEquals(name != null && !name.isBlank() && price != null && price.signum() > 0, validCanonicalItem(name,price));
        assertTrue(restaurantErrors("n","a",1L,8.0,102.0).isEmpty());
        assertTrue(restaurantErrors("n","a",1L,24.0,110.0).isEmpty());
        for (String value : Arrays.asList(null,""," ")) {
            assertEquals(List.of("Restaurant service thiếu tên nhà hàng canonical"),restaurantErrors(value,"a",1L,10.0,106.0));
            assertEquals(List.of("Restaurant service thiếu địa chỉ nhà hàng canonical"),restaurantErrors("n",value,1L,10.0,106.0));
        }
        for (Long owner : Arrays.asList(null,0L,-1L)) assertEquals(List.of("Restaurant service thiếu owner ID canonical"),restaurantErrors("n","a",owner,10.0,106.0));
        for (Double lat : Arrays.asList(null,Double.NaN,Double.POSITIVE_INFINITY,7.9,24.1))
            assertEquals(List.of("Restaurant service thiếu tọa độ nhà hàng canonical trong phạm vi Việt Nam"),restaurantErrors("n","a",1L,lat,106.0));
        for (Double lng : Arrays.asList(null,Double.NaN,Double.NEGATIVE_INFINITY,101.9,110.1))
            assertEquals(List.of("Restaurant service thiếu tọa độ nhà hàng canonical trong phạm vi Việt Nam"),restaurantErrors("n","a",1L,10.0,lng));
        assertEquals(4,restaurantErrors(null,null,null,null,null).size());
    }
}
