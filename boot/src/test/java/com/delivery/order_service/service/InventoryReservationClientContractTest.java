package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReservationClientContractTest {

    @Test
    void reserveCommitAndReleaseKeepOneStableReservationIdentity() {
        UUID reservationId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
            assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
            int call = calls.getAndIncrement();
            if (call == 0) {
                assertThat(request.url().toString()).isEqualTo(
                        "http://restaurant-service/api/menu-items/internal/inventory/reservations");
                return json("""
                        {"status":1,"data":{"reservationId":"%s","orderId":41,
                        "state":"RESERVED","restaurantId":7,"items":[{"menuItemId":9,"quantity":2}]}}
                        """.formatted(reservationId));
            }
            if (call == 1) {
                assertThat(request.url().toString()).isEqualTo(
                        "http://restaurant-service/api/menu-items/internal/inventory/reservations/"
                                + reservationId + "/commit?orderId=41");
                return json("""
                        {"status":1,"data":{"reservationId":"%s","orderId":41,"state":"COMMITTED"}}
                        """.formatted(reservationId));
            }
            assertThat(request.url().toString()).isEqualTo(
                    "http://restaurant-service/api/menu-items/internal/inventory/reservations/"
                            + reservationId + "/release?orderId=41");
            return json("""
                    {"status":1,"data":{"reservationId":"%s","orderId":41,"state":"RELEASED"}}
                    """.formatted(reservationId));
        }).build();

        CreateOrderRequest.OrderItemRequest line = new CreateOrderRequest.OrderItemRequest();
        line.setMenuItemId(9L);
        line.setQuantity(2);
        InventoryReservationClient client = new InventoryReservationClient(webClient,
                "http://restaurant-service", "test-secret", Duration.ofSeconds(2));

        assertThat(client.reserve(reservationId, 41L, 21L, 31L, 7L, List.of(line)).state())
                .isEqualTo("RESERVED");
        assertThat(client.commit(reservationId, 41L).state()).isEqualTo("COMMITTED");
        assertThat(client.release(reservationId, 41L).state()).isEqualTo("RELEASED");
        assertThat(calls).hasValue(3);
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }
}
