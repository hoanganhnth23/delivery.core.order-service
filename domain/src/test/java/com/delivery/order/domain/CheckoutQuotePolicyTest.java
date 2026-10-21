package com.delivery.order.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.delivery.order.domain.CheckoutQuotePolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutQuotePolicyTest {
    private final Instant now = Instant.parse("2026-10-06T00:00:00Z");
    private Quote quote(Long owner, Instant expiry, Long used, String input) { return new Quote(owner,expiry,used,input,"price"); }
    @Test void requiredMissingAndExactExpiry() {
        assertEquals("QUOTE_REQUIRED", assertThrows(Rejected.class, () -> requireId(null)).code());
        requireId(UUID.randomUUID());
        assertEquals("Báo giá không còn hiệu lực", assertThrows(Rejected.class, () -> requireFound(null)).getMessage());
        Quote valid = quote(1L,now.plusNanos(1),null,"input"); assertSame(valid,requireFound(valid));
        validate(valid,1L,() -> now,() -> "input"); consume(valid,1L,() -> now);
        for (Instant expiry : List.of(now.minusNanos(1),now)) {
            Quote expired = quote(1L,expiry,2L,"bad");
            assertEquals("Báo giá đã hết hạn, vui lòng xem giá lại",assertThrows(Rejected.class, () -> validate(expired,1L,() -> now,() -> {throw new AssertionError();})).getMessage());
            assertEquals("Báo giá không còn hiệu lực",assertThrows(Rejected.class, () -> consume(expired,1L,() -> now)).getMessage());
        }
    }
    @Test void ownerExpiryUsedAndInputCartesianPrecedence() {
        for (Long principal : Arrays.asList(null,1L,2L)) for (boolean expired : List.of(false,true)) for (Long used : Arrays.asList(null,3L)) for (String input : List.of("input","changed")) {
            Quote fact = quote(1L,expired ? now : now.plusSeconds(1),used,"input");
            String expected = !Objects.equals(principal,1L) ? "QUOTE_MISMATCH" : expired ? "QUOTE_EXPIRED" : used != null ? "QUOTE_ALREADY_USED" : !input.equals("input") ? "QUOTE_MISMATCH" : null;
            if (expected == null) validate(fact,principal,() -> now,() -> input);
            else assertEquals(expected,assertThrows(Rejected.class, () -> validate(fact,principal,() -> now,() -> input)).code());
            String consumeError = !Objects.equals(principal,1L) || expired ? "QUOTE_EXPIRED" : used != null ? "QUOTE_ALREADY_USED" : null;
            if (consumeError == null) consume(fact,principal,() -> now);
            else assertEquals(consumeError,assertThrows(Rejected.class, () -> consume(fact,principal,() -> now)).code());
        }
        Quote wrong = quote(2L,now,3L,"bad");
        assertThrows(Rejected.class, () -> validate(wrong,1L,() -> {throw new AssertionError();},() -> {throw new AssertionError();}));
        assertThrows(Rejected.class, () -> consume(wrong,1L,() -> {throw new AssertionError();}));
        Quote used = quote(1L,now.plusSeconds(1),3L,"bad");
        assertEquals("QUOTE_ALREADY_USED",assertThrows(Rejected.class, () -> validate(used,1L,() -> now,() -> {throw new AssertionError();})).code());
        assertEquals("Giỏ hàng hoặc địa điểm giao không khớp báo giá",assertThrows(Rejected.class, () -> validate(quote(1L,now.plusSeconds(1),null,"a"),1L,() -> now,() -> "b")).getMessage());
    }
    @Test void repriceTtlAndLegacyVoucherSelection() {
        Quote fact = quote(1L,now,null,"input");
        assertFalse(priceChanged(fact,() -> "price")); assertTrue(priceChanged(fact,() -> "changed"));
        for (Duration ttl : List.of(Duration.ZERO,Duration.ofMinutes(5),Duration.ofNanos(-1))) assertEquals(now.plus(ttl),expiresAt(now,ttl));
        for (List<Long> ids : Arrays.asList(null,List.<Long>of(),List.of(1L),List.of(1L,2L))) for (String mode : Arrays.asList(null,"","AUTO")) {
            VoucherSelection selection = previewSelection(ids,mode);
            assertEquals(ids != null && ids.size()==1 ? 1L : null,selection.voucherId());
            assertEquals(mode != null || ids == null || ids.size()!=1,selection.includeSelectedIds());
        }
    }
}
