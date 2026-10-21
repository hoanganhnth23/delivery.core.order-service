package com.delivery.order_service.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherCheckoutCapabilityTest {
    @Test
    void emptyAllowlistKeepsStackingClosed() {
        VoucherCheckoutCapability capability = new VoucherCheckoutCapability(true, "");

        assertThat(capability.isEnabled(42L)).isFalse();
    }

    @Test
    void stablePrincipalAllowlistEnablesOnlyConfiguredAccounts() {
        VoucherCheckoutCapability capability = new VoucherCheckoutCapability(true, "42, 99, invalid, -3");

        assertThat(capability.isEnabled(42L)).isTrue();
        assertThat(capability.isEnabled(99L)).isTrue();
        assertThat(capability.isEnabled(43L)).isFalse();
        assertThat(capability.isEnabled(null)).isFalse();
    }
}
