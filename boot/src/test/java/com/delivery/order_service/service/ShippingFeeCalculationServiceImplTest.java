package com.delivery.order_service.service;

import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.service.impl.ShippingFeeCalculationServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShippingFeeCalculationServiceImplTest {

    private final ShippingFeeCalculationServiceImpl service =
            new ShippingFeeCalculationServiceImpl();

    @Test
    void validVietnamCoordinatesKeepCanonicalDistancePricing() {
        BigDecimal fee = service.calculateShippingFee(
                10.7769, 106.7009, 10.7900, 106.7009, new BigDecimal("100000"));

        assertThat(fee).isEqualByComparingTo("12000");
    }

    @Test
    void missingCoordinatesFailClosedInsteadOfUsingMinimumFee() {
        assertThatThrownBy(() -> service.calculateShippingFee(
                null, 106.7009, 10.7900, 106.7009, new BigDecimal("100000")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Tọa độ lấy/giao hàng");
    }

    @Test
    void nonFiniteOrOutOfVietnamCoordinatesFailClosed() {
        assertThatThrownBy(() -> service.calculateShippingFee(
                Double.NaN, 106.7009, 10.7900, 106.7009, new BigDecimal("100000")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.calculateShippingFee(
                10.7769, 106.7009, 25.0, 106.7009, new BigDecimal("100000")))
                .isInstanceOf(ValidationException.class);
    }
}
