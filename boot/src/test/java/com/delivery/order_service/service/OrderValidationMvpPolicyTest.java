package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.config.OrderRestaurantCircuitBreaker;
import com.delivery.order_service.config.RestaurantCallResilienceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderValidationMvpPolicyTest {

    @Mock
    private WebClient webClient;

    @Test
    void onlinePaymentIsRejectedBeforeCallingRestaurantService() {
        CreateOrderRequest request = validCodRequest();
        request.setPaymentMethod("ONLINE");

        OrderValidationService service = new OrderValidationService(
                webClient,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, 21L));
        verifyNoInteractions(webClient);
    }

    @Test
    void voucherCheckoutIsClosedUntilDiscountAndCompensationAreProven() {
        CreateOrderRequest request = validCodRequest();
        request.setVoucherIds(List.of(3L));

        OrderValidationService service = new OrderValidationService(
                webClient,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, 21L));
        verifyNoInteractions(webClient);
    }

    @Test
    void flashSaleCheckoutIsClosedUntilReservationIsRecoverable() {
        CreateOrderRequest request = validCodRequest();
        request.getItems().get(0).setFlashSaleItemId(4L);

        OrderValidationService service = new OrderValidationService(
                webClient,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, 21L));
        verifyNoInteractions(webClient);
    }

    @Test
    void livestreamCheckoutIsClosedByDefault() {
        CreateOrderRequest request = validCodRequest();
        request.setLivestreamId(UUID.randomUUID());
        OrderValidationService service = new OrderValidationService(
                webClient, "http://restaurant-service:8083", "test-secret", circuitBreaker());

        assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, 21L));
        verifyNoInteractions(webClient);
    }

    @Test
    void livestreamAndFlashSaleCannotBeCombined() {
        CreateOrderRequest request = validCodRequest();
        request.setLivestreamId(UUID.randomUUID());
        request.getItems().get(0).setFlashSaleItemId(4L);
        OrderValidationService service = new OrderValidationService(
                webClient, "http://restaurant-service:8083", "test-secret", circuitBreaker());
        ReflectionTestUtils.setField(service, "livestreamCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "flashSaleCheckoutEnabled", true);

        assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, 21L));
        verifyNoInteractions(webClient);
    }

    @Test
    void admissionAccumulatesExactErrorsBeforeAnyRemoteLookup() {
        CreateOrderRequest request = validCodRequest();
        request.setRestaurantId(null);
        request.setPaymentMethod("ONLINE");
        request.setItems(java.util.Arrays.asList(null, request.getItems().get(0), request.getItems().get(0)));
        request.setDeliveryLat(null);
        OrderValidationService service = new OrderValidationService(webClient,
                "http://restaurant-service:8083", "test-secret", circuitBreaker());

        ValidationException failure = assertThrows(ValidationException.class,
                () -> service.validateCreateOrderRequest(request, null));
        org.junit.jupiter.api.Assertions.assertEquals("Dữ liệu đơn hàng không hợp lệ: "
                + "Restaurant ID không được để trống, MVP hiện chỉ hỗ trợ thanh toán COD, "
                + "Sản phẩm 1: dữ liệu sản phẩm không hợp lệ, Sản phẩm 3: Menu Item ID bị trùng, "
                + "Tọa độ giao hàng (latitude và longitude) là bắt buộc, User ID không hợp lệ", failure.getMessage());
        verifyNoInteractions(webClient);
    }

    private OrderRestaurantCircuitBreaker circuitBreaker() {
        return new OrderRestaurantCircuitBreaker(new RestaurantCallResilienceProperties(), new SimpleMeterRegistry());
    }

    private CreateOrderRequest validCodRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurantId(7L);
        request.setDeliveryAddress("123 Test");
        request.setDeliveryLat(10.8);
        request.setDeliveryLng(106.7);
        request.setCustomerName("Khách hàng");
        request.setCustomerPhone("0901234567");
        request.setPaymentMethod("COD");

        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(9L);
        item.setQuantity(1);
        request.setItems(List.of(item));
        return request;
    }
}
