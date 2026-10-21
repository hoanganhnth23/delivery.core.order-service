package com.delivery.order_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable-principal canary gate for stacked-voucher checkout. */
@Component
public class VoucherCheckoutCapability {
    private final boolean enabled;
    private final Set<Long> canaryPrincipals;

    public VoucherCheckoutCapability(
            @Value("${app.order.voucher-stacking-enabled:false}") boolean enabled,
            @Value("${app.order.voucher-stacking-canary-principals:}") String principals) {
        this.enabled = enabled;
        this.canaryPrincipals = parse(principals);
    }

    public boolean isEnabled(Long principalId) {
        return enabled && principalId != null && canaryPrincipals.contains(principalId);
    }

    private Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Collections.emptySet();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank())
                .map(item -> { try { return Long.valueOf(item); } catch (NumberFormatException ignored) { return null; } })
                .filter(item -> item != null && item > 0)
                .collect(Collectors.toUnmodifiableSet());
    }
}
