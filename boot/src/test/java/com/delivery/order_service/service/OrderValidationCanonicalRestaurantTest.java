package com.delivery.order_service.service;

import com.delivery.order_service.dto.internal.ValidatedOrderData;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.config.OrderRestaurantCircuitBreaker;
import com.delivery.order_service.config.RestaurantCallResilienceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderValidationCanonicalRestaurantTest {

    @Test
    void completeCanonicalRestaurantPayloadIsAccepted() {
        OrderValidationService service = serviceWithResponse("""
                {"status":1,"data":{
                  "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                    "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000}]}}
                """);

        ValidatedOrderData result = service.validateCreateOrderRequest(validRequest(), 21L);

        assertThat(result.creatorId()).isEqualTo(11L);
        assertThat(result.restaurantName()).isEqualTo("Quán A");
        assertThat(result.pickupLat()).isEqualTo(10.76);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void missingCanonicalRestaurantCoordinatesFailClosed() {
        OrderValidationService service = serviceWithResponse("""
                {"status":1,"data":{
                  "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                    "restaurantPhone":"0900000000","creatorId":11},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000}]}}
                """);

        assertThatThrownBy(() -> service.validateCreateOrderRequest(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tọa độ nhà hàng canonical");
    }

    @Test
    void nonPositiveCanonicalMenuPriceFailsClosed() {
        OrderValidationService service = serviceWithResponse("""
                {"status":1,"data":{
                  "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                    "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":0}]}}
                """);

        assertThatThrownBy(() -> service.validateCreateOrderRequest(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dữ liệu canonical cho món");
    }

    @Test
    void deliveryCoordinatesAreSentForTheServiceabilityCheck() {
        AtomicReference<String> sent = new AtomicReference<>();
        OrderValidationService service = serviceWithResponse("""
                {"status":1,"data":{
                  "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                    "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000}]}}
                """, sent);

        service.validateCreateOrderRequest(validRequest(), 21L);

        assertThat(sent.get()).contains("\"deliveryLat\":10.78").contains("\"deliveryLng\":106.68");
    }

    private OrderValidationService serviceWithResponse(String json) {
        return serviceWithResponse(json, new AtomicReference<>());
    }

    private OrderValidationService serviceWithResponse(String json, AtomicReference<String> sentBody) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
                    MockClientHttpRequest captured = new MockClientHttpRequest(request.method(), request.url());
                    request.body().insert(captured, new BodyInserter.Context() {
                        @Override public List<HttpMessageWriter<?>> messageWriters() {
                            return ExchangeStrategies.withDefaults().messageWriters();
                        }
                        @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                        @Override public Map<String, Object> hints() { return Map.of(); }
                    }).block();
                    sentBody.set(captured.getBodyAsString().block());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(json)
                            .build());
                })
                .build();
        return new OrderValidationService(
                webClient,
                "http://restaurant-service:8083",
                "test-secret", new OrderRestaurantCircuitBreaker(
                        new RestaurantCallResilienceProperties(), new SimpleMeterRegistry()));
    }

    private CreateOrderRequest validRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurantId(7L);
        request.setDeliveryAddress("456 Đường B, TP.HCM");
        request.setDeliveryLat(10.78);
        request.setDeliveryLng(106.68);
        request.setCustomerName("Khách hàng");
        request.setCustomerPhone("0900000000");
        request.setPaymentMethod("COD");

        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(5L);
        item.setQuantity(2);
        request.setItems(List.of(item));
        return request;
    }
}
