package com.delivery.order.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Canonical money arithmetic: no implicit currency scaling or rounding. */
public final class CheckoutPricingPolicy {
    private CheckoutPricingPolicy() { }

    @FunctionalInterface
    public interface MenuPricePort { BigDecimal price(Long menuItemId); }
    public record FlashPrice(Long menuItemId, Integer quantity, BigDecimal unitPrice) { }
    public record Line(BigDecimal unitPrice, Integer quantity) { }

    public static boolean flashMatches(Long menuItemId, Integer quantity, FlashPrice flash) {
        return flash != null && menuItemId.equals(flash.menuItemId()) && quantity.equals(flash.quantity());
    }

    /** Regular facts are required even when livestream replaces their monetary value. */
    public static BigDecimal regularOrLivestream(Long menuItemId, MenuPricePort menu,
                                                 Map<Long, BigDecimal> livestreamPrices) {
        return livestreamPrices.getOrDefault(menuItemId, menu.price(menuItemId));
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, Integer quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public static BigDecimal subtotal(java.util.stream.Stream<Line> lines) {
        return lines.map(line -> lineTotal(line.unitPrice(), line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static boolean validDiscount(BigDecimal discount, BigDecimal subtotal, BigDecimal shippingFee) {
        return discount.signum() >= 0 && discount.compareTo(subtotal.add(shippingFee)) <= 0;
    }

    public static BigDecimal customerShipping(BigDecimal gross, BigDecimal shippingDiscount,
                                               BigDecimal quotedCustomerShipping) {
        return quotedCustomerShipping == null ? gross.subtract(shippingDiscount).max(BigDecimal.ZERO)
                : quotedCustomerShipping;
    }

    public static BigDecimal total(BigDecimal subtotal, BigDecimal itemDiscount, BigDecimal customerShipping) {
        return subtotal.subtract(itemDiscount).add(customerShipping);
    }

    public static boolean positivePayableFood(BigDecimal total, BigDecimal grossShipping) {
        return total.compareTo(grossShipping) > 0;
    }

    public static boolean validCanonicalItem(String name, BigDecimal price) {
        return name != null && !name.isBlank() && price != null && price.signum() > 0;
    }

    public static List<String> restaurantErrors(String name, String address, Long creatorId,
                                                 Double lat, Double lng) {
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        if (name == null || name.isBlank()) errors.add("Restaurant service thiếu tên nhà hàng canonical");
        if (address == null || address.isBlank()) errors.add("Restaurant service thiếu địa chỉ nhà hàng canonical");
        if (creatorId == null || creatorId <= 0) errors.add("Restaurant service thiếu owner ID canonical");
        if (!finiteInRange(lat, 8.0, 24.0) || !finiteInRange(lng, 102.0, 110.0))
            errors.add("Restaurant service thiếu tọa độ nhà hàng canonical trong phạm vi Việt Nam");
        return errors;
    }

    private static boolean finiteInRange(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }
}
