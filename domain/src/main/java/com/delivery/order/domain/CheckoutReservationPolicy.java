package com.delivery.order.domain;
import java.util.ArrayList;
import java.util.List;
public final class CheckoutReservationPolicy {
    private CheckoutReservationPolicy() {}
    public enum Rail { NONE, LEGACY, PROMOTION }
    public static List<Long> selectedIds(List<Long> values) {
        List<Long> ids = values == null ? new ArrayList<>() : new ArrayList<>(values);
        if (ids.size() > 3 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("At most three distinct voucher IDs are supported");
        return ids;
    }
    public static boolean needsAuto(String mode, List<Long> ids) {
        return "AUTO".equalsIgnoreCase(mode) && ids.isEmpty();
    }
    public static Rail rail(String mode, List<Long> ids) {
        if ("MANUAL".equalsIgnoreCase(mode) && ids.isEmpty())
            throw new IllegalArgumentException("Manual voucher mode requires selected voucher IDs");
        if (!ids.isEmpty() && (ids.size() > 1 || mode != null)) return Rail.PROMOTION;
        return ids.size() == 1 ? Rail.LEGACY : Rail.NONE;
    }
}
