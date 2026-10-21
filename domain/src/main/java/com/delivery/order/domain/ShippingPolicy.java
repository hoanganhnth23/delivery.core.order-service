package com.delivery.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Existing Vietnam/Haversine shipping policy, including the two rounding stages. */
public final class ShippingPolicy {
    private ShippingPolicy() { }

    public static boolean validCoordinates(Double pickupLat, Double pickupLng,
                                            Double deliveryLat, Double deliveryLng) {
        return finiteInRange(pickupLat, 8.0, 24.0) && finiteInRange(deliveryLat, 8.0, 24.0)
                && finiteInRange(pickupLng, 102.0, 110.0) && finiteInRange(deliveryLng, 102.0, 110.0);
    }

    private static boolean finiteInRange(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371 * c;
    }

    public static BigDecimal fee(double distanceKm) {
        BigDecimal shippingFee = new BigDecimal("12000");
        if (distanceKm > 2) {
            BigDecimal extraFee = new BigDecimal("4500")
                    .multiply(BigDecimal.valueOf(distanceKm - 2)).setScale(0, RoundingMode.UP);
            shippingFee = shippingFee.add(extraFee);
        }
        shippingFee = shippingFee.max(new BigDecimal("12000")).min(new BigDecimal("50000"));
        return shippingFee.divide(new BigDecimal("500"), 0, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("500"));
    }
}
