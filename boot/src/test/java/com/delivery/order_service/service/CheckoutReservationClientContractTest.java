package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CreateOrderRequest;
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

class CheckoutReservationClientContractTest {

    @Test
    void voucherReserveUsesExplicitServicePortInternalCredentialAndStableIdentity() {
        UUID reservationId = UUID.randomUUID();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
            assertThat(request.url().toString())
                    .isEqualTo("http://promotion-service:8096/api/promotions/internal/reserve");
            assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
            return json("""
                    {"status":1,"data":{"reservationId":"%s","orderId":41,
                    "voucherId":11,"state":"RESERVED","discountAmount":10000,
                    "itemDiscount":0,"shippingDiscount":10000,"customerShippingFee":5000,
                    "grossShippingFee":15000,"platformSubsidy":10000,"shopDiscount":0,
                    "layer":"FREESHIP","fundingSource":"PLATFORM","discountBase":15000}}
                    """.formatted(reservationId));
        }).build();

        CheckoutReservationClient client = client(webClient);
        CheckoutReservationClient.VoucherQuote quote = client.reserveVoucher(
                reservationId, 41L, 21L, 31L, 11L,
                new BigDecimal("45000"), new BigDecimal("15000"));

        assertThat(quote.discountAmount()).isEqualByComparingTo("10000");
        assertThat(quote.itemDiscount()).isEqualByComparingTo("0");
        assertThat(quote.shippingDiscount()).isEqualByComparingTo("10000");
        assertThat(quote.customerShippingFee()).isEqualByComparingTo("5000");
        assertThat(quote.platformSubsidy()).isEqualByComparingTo("10000");
        assertThat(quote.breakdown()).singleElement()
                .satisfies(line -> assertThat(line.get("layer")).isEqualTo("FREESHIP"));
    }

    @Test
    void stackedReleaseCarriesTheStablePrincipalOwnershipProof() {
        UUID reservationId = UUID.randomUUID();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo(
                    "http://promotion-service:8096/api/promotions/internal/promotion-reservations/"
                            + reservationId + "/release?orderId=41&userPrincipalId=31");
            assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
            return json("{\"status\":1,\"data\":{}}");
        }).build();

        client(webClient).releaseVouchers(reservationId, 41L, 31L);
    }

    @Test
    void stackingQuoteNormalizesCalculateAttributionKeysForOrderPreview() {
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo(
                    "http://promotion-service:8096/api/promotions/internal/calculate");
            return json("""
                    {"status":1,"data":{"selectedVoucherIds":[11],
                    "itemDiscount":10000,"shippingDiscount":0,"totalDiscount":10000,
                    "customerShippingFee":15000,
                    "appliedVouchers":[{"id":11,"code":"SAVE10","layer":"PLATFORM_DISCOUNT",
                    "fundingSource":"PLATFORM","discountBase":100000,"discountAmount":10000}]}}
                    """);
        }).build();

        CheckoutReservationClient.PromotionQuote quote = client(webClient).quoteVouchers(
                21L, 31L, 7L, new BigDecimal("100000"), new BigDecimal("15000"),
                List.of(11L), "MANUAL");

        assertThat(quote.breakdown()).singleElement().satisfies(line -> {
            assertThat(line.get("voucherId")).isEqualTo(11);
            assertThat(line.get("voucherCode")).isEqualTo("SAVE10");
        });
    }

    @Test
    void flashReserveAndReleaseUseExplicitServicePortAndSameIdentity() {
        UUID reservationId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.POST);
            assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
            if (calls.getAndIncrement() == 0) {
                assertThat(request.url().toString())
                        .isEqualTo("http://flashsale-service:8092/api/flashsales/internal/reserve");
                return json("""
                        {"status":1,"data":{"reservationId":"%s","orderId":41,
                        "state":"RESERVED","items":[{"flashSaleItemId":71,"menuItemId":51,
                        "quantity":1,"unitPrice":30000}]}}
                        """.formatted(reservationId));
            }
            assertThat(request.url().toString()).isEqualTo(
                    "http://flashsale-service:8092/api/flashsales/internal/reservations/"
                            + reservationId + "/release?orderId=41");
            return json("{\"status\":1,\"data\":{}}");
        }).build();

        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(51L);
        item.setFlashSaleItemId(71L);
        item.setQuantity(1);
        CheckoutReservationClient client = client(webClient);

        CheckoutReservationClient.FlashQuote quote = client.reserveFlash(
                reservationId, 41L, 21L, 11L, List.of(item));
        client.releaseFlash(reservationId, 41L);

        assertThat(quote.byFlashSaleItemId().get(71L).unitPrice())
                .isEqualByComparingTo("30000");
        assertThat(calls).hasValue(2);
    }

    private CheckoutReservationClient client(WebClient webClient) {
        return new CheckoutReservationClient(webClient,
                "http://promotion-service:8096", "http://flashsale-service:8092",
                "test-secret", 2000);
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }
}
