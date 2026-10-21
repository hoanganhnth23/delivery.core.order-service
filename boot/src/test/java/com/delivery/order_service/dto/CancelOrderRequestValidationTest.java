package com.delivery.order_service.dto;

import com.delivery.order_service.dto.request.CancelOrderRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CancelOrderRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void reasonIsOptionalButBounded() {
        CancelOrderRequest empty = new CancelOrderRequest();
        CancelOrderRequest tooLong = new CancelOrderRequest();
        tooLong.setReason("x".repeat(501));

        assertThat(validator.validate(empty)).isEmpty();
        assertThat(validator.validate(tooLong)).isNotEmpty();
    }
}
