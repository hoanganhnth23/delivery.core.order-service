package com.delivery.order_service.dto;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutPreviewRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMissingItemsAndCoordinatesBeforeServiceCalls() {
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        request.setRestaurantId(1L);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("items", "deliveryLat", "deliveryLng");
    }

    @Test
    void validatesNestedItemQuantityAndVietnamCoordinateBounds() {
        CheckoutPreviewRequest.PreviewItem item = new CheckoutPreviewRequest.PreviewItem();
        item.setMenuItemId(3L);
        item.setQuantity(0);
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        request.setRestaurantId(1L);
        request.setDeliveryLat(40.0);
        request.setDeliveryLng(80.0);
        request.setItems(List.of(item));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("items[0].quantity", "deliveryLat", "deliveryLng");
    }
}
