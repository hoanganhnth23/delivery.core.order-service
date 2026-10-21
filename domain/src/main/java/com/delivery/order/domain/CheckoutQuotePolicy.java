package com.delivery.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** Quote decisions only; lookup, fingerprints, current pricing and locking remain adapter facts. */
public final class CheckoutQuotePolicy {
    private CheckoutQuotePolicy() { }
    public record Quote(Long principalId, Instant expiresAt, Long consumedOrderId,
                        String inputFingerprint, String pricingFingerprint) { }
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    public static void requireId(Object id) {
        if (id == null) throw new Rejected("QUOTE_REQUIRED", "Cần báo giá hợp lệ trước khi đặt đơn");
    }
    public static Quote requireFound(Quote quote) {
        if (quote == null) throw new Rejected("QUOTE_EXPIRED", "Báo giá không còn hiệu lực");
        return quote;
    }
    public static void validate(Quote quote, Long principalId, Supplier<Instant> now, Supplier<String> input) {
        if (!quote.principalId().equals(principalId))
            throw new Rejected("QUOTE_MISMATCH", "Báo giá không thuộc khách hàng hiện tại");
        if (!quote.expiresAt().isAfter(now.get()))
            throw new Rejected("QUOTE_EXPIRED", "Báo giá đã hết hạn, vui lòng xem giá lại");
        requireUnused(quote);
        if (!quote.inputFingerprint().equals(input.get()))
            throw new Rejected("QUOTE_MISMATCH", "Giỏ hàng hoặc địa điểm giao không khớp báo giá");
    }
    public static void consume(Quote quote, Long principalId, Supplier<Instant> now) {
        if (!quote.principalId().equals(principalId) || !quote.expiresAt().isAfter(now.get()))
            throw new Rejected("QUOTE_EXPIRED", "Báo giá không còn hiệu lực");
        requireUnused(quote);
    }
    private static void requireUnused(Quote quote) {
        if (quote.consumedOrderId() != null)
            throw new Rejected("QUOTE_ALREADY_USED", "Báo giá này đã được sử dụng");
    }
    public static boolean priceChanged(Quote quote, Supplier<String> currentFingerprint) {
        return !quote.pricingFingerprint().equals(currentFingerprint.get());
    }
    public static Instant expiresAt(Instant now, Duration ttl) { return now.plus(ttl); }
    public record VoucherSelection(Long voucherId, boolean includeSelectedIds) { }
    public static VoucherSelection previewSelection(java.util.List<Long> ids, String mode) {
        return new VoucherSelection(ids != null && ids.size() == 1 ? ids.get(0) : null,
                mode != null || ids == null || ids.size() != 1);
    }
}
