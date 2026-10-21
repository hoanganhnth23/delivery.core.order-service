package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutFingerprintLivestreamTest {
    private final CheckoutFingerprintService service = new CheckoutFingerprintService();

    @Test
    void pricingFingerprintsBindTheLivestreamSource() {
        assertThat(CheckoutFingerprintService.VERSION).isEqualTo("v2");
        UUID livestreamId = UUID.randomUUID();
        CheckoutPreviewRequest preview = previewRequest();
        String previewWithout = service.pricingInput(preview);
        preview.setLivestreamId(livestreamId);

        CreateOrderRequest create = createRequest();
        String createWithout = service.pricingInput(create);
        create.setLivestreamId(livestreamId);

        assertThat(service.pricingInput(preview)).isNotEqualTo(previewWithout);
        assertThat(service.pricingInput(create)).isNotEqualTo(createWithout);
    }

    private CheckoutPreviewRequest previewRequest() {
        var request = new CheckoutPreviewRequest();
        request.setRestaurantId(7L);
        request.setDeliveryLat(10.8);
        request.setDeliveryLng(106.7);
        var item = new CheckoutPreviewRequest.PreviewItem();
        item.setMenuItemId(9L);
        item.setQuantity(1);
        request.setItems(List.of(item));
        return request;
    }

    private CreateOrderRequest createRequest() {
        var request = new CreateOrderRequest();
        request.setRestaurantId(7L);
        request.setDeliveryLat(10.8);
        request.setDeliveryLng(106.7);
        var item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(9L);
        item.setQuantity(1);
        request.setItems(List.of(item));
        return request;
    }
}
