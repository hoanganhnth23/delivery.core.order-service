package com.delivery.order_service.service;

import com.delivery.order_service.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LivestreamCheckoutPriceClientTest {

    @Test
    void resolvesStrictlyScopedAuthoritativePrices() {
        UUID livestreamId = UUID.randomUUID();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
            assertThat(request.url().toString())
                    .isEqualTo("http://livestream-service:8094/api/livestreams/internal/checkout-quote");
            assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("""
                            {"status":1,"data":{"livestreamId":"%s","restaurantId":42,
                            "items":[{"productId":10,"priceAtLive":89000},{"productId":20,"priceAtLive":99000}]}}
                            """.formatted(livestreamId))
                    .build());
        }).build();
        var client = new LivestreamCheckoutPriceClient(
                webClient, "http://livestream-service:8094", "test-secret", true);

        var prices = client.resolve(livestreamId, 42L, List.of(10L, 20L, 30L));

        assertThat(prices).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                10L, new BigDecimal("89000"), 20L, new BigDecimal("99000")));
    }

    @Test
    void disabledCapabilityFailsBeforeCallingLivestreamService() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.error(new AssertionError("must not call dependency"));
        }).build();
        var client = new LivestreamCheckoutPriceClient(
                webClient, "http://livestream-service:8094", "test-secret", false);

        assertThatThrownBy(() -> client.resolve(UUID.randomUUID(), 42L, List.of(10L)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("disabled");
        assertThat(calls).hasValue(0);
    }

    @Test
    void mismatchedResponseFailsClosed() {
        UUID requestedId = UUID.randomUUID();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("""
                                {"status":1,"data":{"livestreamId":"%s","restaurantId":43,
                                "items":[{"productId":999,"priceAtLive":1}]}}
                                """.formatted(requestedId))
                        .build())).build();
        var client = new LivestreamCheckoutPriceClient(
                webClient, "http://livestream-service:8094", "test-secret", true);

        assertThatThrownBy(() -> client.resolve(requestedId, 42L, List.of(10L)))
                .hasMessageContaining("malformed");
    }
}
