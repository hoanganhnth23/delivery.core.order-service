package com.delivery.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.config.OrderRestaurantCircuitBreaker;
import com.delivery.order_service.config.RestaurantCallResilienceProperties;
import com.delivery.routing.client.RoutingClient;
import com.delivery.routing.client.RoutingClientException;
import com.delivery.routing.client.RoutingClientFailure;
import com.delivery.routing.contracts.Coordinate;
import com.delivery.routing.contracts.EtaWindowRequest;
import com.delivery.routing.contracts.EtaWindowResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import reactor.core.publisher.Mono;

class CheckoutPreviewMvpPolicyTest {

    @Test
    void livestreamPriceOverridesOnlyThePinnedServerQuotedItem() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("80000")))
                .thenReturn(new BigDecimal("15000"));
        LivestreamCheckoutPriceClient livePrices = mock(LivestreamCheckoutPriceClient.class);
        CheckoutPreviewService service = serviceWithCanonicalMenu(shippingFeeService);
        ReflectionTestUtils.setField(service, "livestreamPriceClient", livePrices);
        CheckoutPreviewRequest request = validRequest();
        request.setLivestreamId(UUID.randomUUID());
        when(livePrices.resolve(request.getLivestreamId(), 7L, List.of(5L)))
                .thenReturn(Map.of(5L, new BigDecimal("40000")));

        var preview = service.calculatePreview(request, 21L);

        assertThat(preview.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getUnitPrice()).isEqualByComparingTo("40000"));
        assertThat(preview.getSubtotal()).isEqualByComparingTo("80000");
        assertThat(preview.getTotalPrice()).isEqualByComparingTo("95000");
    }

    @Test
    void couponCodeIsRejectedBeforeRestaurantLookupInCodMvp() {
        WebClient webClient = mock(WebClient.class);
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                webClient,
                shippingFeeService,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        request.setCouponCode("WELCOME");

        assertThrows(ValidationException.class, () -> service.calculatePreview(request, 21L));
        verifyNoInteractions(webClient, shippingFeeService);
    }

    @Test
    void oneSelectedVoucherRequiresAnExplicitStackingMode() {
        WebClient webClient = mock(WebClient.class);
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                webClient, shippingFeeService, "http://restaurant-service:8083",
                "test-secret", circuitBreaker());
        CheckoutPreviewRequest request = validRequest();
        request.setSelectedVoucherIds(List.of(55L));

        assertThatThrownBy(() -> service.calculatePreview(request, 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("selectionMode");
        verifyNoInteractions(webClient, shippingFeeService);
    }

    @Test
    void manualModeRequiresAtLeastOneSelectedVoucher() {
        WebClient webClient = mock(WebClient.class);
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                webClient, shippingFeeService, "http://restaurant-service:8083",
                "test-secret", circuitBreaker());
        CheckoutPreviewRequest request = validRequest();
        request.setSelectedVoucherIds(List.of());
        request.setSelectionMode("MANUAL");

        assertThatThrownBy(() -> service.calculatePreview(request, 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requires selected voucher IDs");
        verifyNoInteractions(webClient, shippingFeeService);
    }

    @Test
    void missingInternalCredentialFailsBeforeRestaurantLookup() {
        WebClient webClient = mock(WebClient.class);
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                webClient,
                shippingFeeService,
                "http://restaurant-service:8083",
                "", circuitBreaker());

        assertThatThrownBy(() -> service.calculatePreview(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("internal credential");
        verifyNoInteractions(webClient, shippingFeeService);
    }

    @Test
    void previewUsesInternalValidationContractAndCanonicalPickupCoordinates() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));

        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":1,"data":{
                          "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                            "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11,
                            "isAvailable":true},
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                            "isAvailable":true,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        var preview = service.calculatePreview(validRequest(), 21L);

        assertThat(preview.getRestaurantName()).isEqualTo("Quán A");
        assertThat(preview.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getMenuItemName()).isEqualTo("Cơm");
                    assertThat(item.getUnitPrice()).isEqualByComparingTo("50000");
                    assertThat(item.getLineTotal()).isEqualByComparingTo("100000");
                });
        assertThat(preview.getSubtotal()).isEqualByComparingTo("100000");
        assertThat(preview.getShippingFee()).isEqualByComparingTo("15000");
        assertThat(preview.getTotalPrice()).isEqualByComparingTo("115000");
        assertThat(preview.getUnavailableItemIds()).isEmpty();
    }

    @Test
    void enabledVoucherPreviewUsesServerQuoteAndReturnsCanonicalTotal() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        CheckoutReservationClient reservationClient = mock(CheckoutReservationClient.class);
        when(reservationClient.quoteVoucher(21L, 55L, 7L,
                new BigDecimal("100000"), new BigDecimal("15000")))
                .thenReturn(new CheckoutReservationClient.VoucherQuote(new BigDecimal("20000")));
        CheckoutPreviewService service = serviceWithCanonicalMenu(shippingFeeService);
        ReflectionTestUtils.setField(service, "voucherCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "reservationClient", reservationClient);
        CheckoutPreviewRequest request = validRequest();
        request.setVoucherId(55L);

        var preview = service.calculatePreview(request, 21L);

        assertThat(preview.getVoucherId()).isEqualTo(55L);
        assertThat(preview.getDiscountAmount()).isEqualByComparingTo("20000");
        assertThat(preview.getTotalPrice()).isEqualByComparingTo("95000");
    }

    @Test
    void legacyFreeshipQuoteKeepsCanonicalFundingAndShippingAttribution() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        CheckoutReservationClient reservationClient = mock(CheckoutReservationClient.class);
        var line = java.util.Map.<String, Object>of(
                "voucherId", 55L, "code", "FREE15", "layer", "FREESHIP",
                "fundingSource", "PLATFORM", "discountBase", new BigDecimal("15000"),
                "discountAmount", new BigDecimal("15000"));
        when(reservationClient.quoteVoucher(21L, 55L, 7L,
                new BigDecimal("100000"), new BigDecimal("15000")))
                .thenReturn(new CheckoutReservationClient.VoucherQuote(
                        new BigDecimal("15000"), BigDecimal.ZERO, new BigDecimal("15000"),
                        BigDecimal.ZERO, new BigDecimal("15000"), new BigDecimal("15000"),
                        BigDecimal.ZERO, "[]", List.of(line)));
        CheckoutPreviewService service = serviceWithCanonicalMenu(shippingFeeService);
        ReflectionTestUtils.setField(service, "voucherCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "reservationClient", reservationClient);
        CheckoutPreviewRequest request = validRequest();
        request.setVoucherId(55L);

        var preview = service.calculatePreview(request, 21L);

        assertThat(preview.getItemDiscount()).isEqualByComparingTo("0");
        assertThat(preview.getShippingDiscount()).isEqualByComparingTo("15000");
        assertThat(preview.getCustomerShippingFee()).isEqualByComparingTo("0");
        assertThat(preview.getPlatformSubsidy()).isEqualByComparingTo("15000");
        assertThat(preview.getShopDiscount()).isEqualByComparingTo("0");
        assertThat(preview.getTotalPrice()).isEqualByComparingTo("100000");
        assertThat(preview.getAppliedVouchers()).singleElement()
                .satisfies(applied -> assertThat(applied.getLayer()).isEqualTo("FREESHIP"));
    }

    @Test
    void stackedQuoteCannotLeaveAZeroPayableFoodAmount() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        CheckoutReservationClient reservationClient = mock(CheckoutReservationClient.class);
        when(reservationClient.quoteVouchers(21L, 21L, 7L,
                new BigDecimal("100000"), new BigDecimal("15000"), List.of(55L), "MANUAL"))
                .thenReturn(new CheckoutReservationClient.PromotionQuote(
                        null, new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"),
                        new BigDecimal("15000"), List.of(55L), "[]", List.of(),
                        new BigDecimal("100000"), BigDecimal.ZERO));
        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":1,"data":{
                          "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                            "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11,
                            "isAvailable":true},
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                            "isAvailable":true,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService, "http://restaurant-service:8083", "test-secret", circuitBreaker(),
                new VoucherCheckoutCapability(true, "21"));
        ReflectionTestUtils.setField(service, "reservationClient", reservationClient);
        CheckoutPreviewRequest request = validRequest();
        request.setSelectedVoucherIds(List.of(55L));
        request.setSelectionMode("MANUAL");

        assertThatThrownBy(() -> service.calculatePreview(request, 21L, 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("số tiền món dương");
    }

    @Test
    void enabledFlashPreviewUsesServerFlashPrice() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("120000")))
                .thenReturn(new BigDecimal("15000"));
        CheckoutReservationClient reservationClient = mock(CheckoutReservationClient.class);
        when(reservationClient.quoteFlash(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new CheckoutReservationClient.FlashQuote(java.util.Map.of(88L,
                        new CheckoutReservationClient.FlashLine(88L, 5L, 2, new BigDecimal("60000")))));
        CheckoutPreviewService service = serviceWithCanonicalMenu(shippingFeeService);
        ReflectionTestUtils.setField(service, "flashSaleCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "reservationClient", reservationClient);
        CheckoutPreviewRequest request = validRequest();
        request.getItems().get(0).setFlashSaleItemId(88L);

        var preview = service.calculatePreview(request, 21L);

        assertThat(preview.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getUnitPrice()).isEqualByComparingTo("60000"));
        assertThat(preview.getSubtotal()).isEqualByComparingTo("120000");
        assertThat(preview.getTotalPrice()).isEqualByComparingTo("135000");
    }

    @Test
    void voucherAndFlashSelectionIsRejectedBeforeAnyDependencyCall() {
        WebClient webClient = mock(WebClient.class);
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(webClient, shippingFeeService,
                "http://restaurant-service:8083", "test-secret", circuitBreaker());
        ReflectionTestUtils.setField(service, "voucherCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "flashSaleCheckoutEnabled", true);
        ReflectionTestUtils.setField(service, "reservationClient", mock(CheckoutReservationClient.class));
        CheckoutPreviewRequest request = validRequest();
        request.setVoucherId(55L);
        request.getItems().get(0).setFlashSaleItemId(88L);

        assertThatThrownBy(() -> service.calculatePreview(request, 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("không được áp dụng cùng");
        verifyNoInteractions(webClient, shippingFeeService);
    }

    @Test
    void missingCanonicalPickupCoordinatesFailClosedInsteadOfZeroShippingFee() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":1,"data":{
                          "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                            "restaurantPhone":"0900000000","creatorId":11,"isAvailable":true},
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                            "isAvailable":true,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        assertThatThrownBy(() -> service.calculatePreview(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tọa độ pickup latitude");
        verifyNoInteractions(shippingFeeService);
    }

    @Test
    void unavailableRestaurantValidationFailsClosed() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":0,"data":{
                          "restaurantInfo":{"restaurantName":"Quán A","latitude":10.76,"longitude":106.66},
                          "errors":[{"message":"Món ăn Cơm không khả dụng"}],
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                            "isAvailable":false,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService,
                "http://restaurant-service:8083",
                "test-secret", circuitBreaker());

        assertThatThrownBy(() -> service.calculatePreview(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Món ăn Cơm không khả dụng");
        verifyNoInteractions(shippingFeeService);
    }

    @Test
    void enabledEtaWindowUsesTypedRoutingClientAndPrepEstimate() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        RoutingClient routingClient = mock(RoutingClient.class);
        when(routingClient.getEtaWindow(new EtaWindowRequest(
                new Coordinate(10.76, 106.66), new Coordinate(10.78, 106.68), 15)))
                .thenReturn(new EtaWindowResponse(27, 37, "MAPBOX_DIRECTIONS"));
        WebClient webClient = internalValidationWebClient("""
                {"status":1,"data":{"restaurantInfo":{
                  "restaurantName":"Quán A","latitude":10.76,"longitude":106.66,
                  "isAvailable":true,"serviceabilityEnabled":true,"serviceable":true,
                  "serviceabilityZoneId":9,"serviceabilityZoneRevision":2,
                  "defaultPrepTimeMinutes":15},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm",
                  "actualPrice":50000,"isAvailable":true,"hasEnoughStock":true}]}}
                """);
        CheckoutPreviewService service = new CheckoutPreviewService(
                webClient, shippingFeeService, "http://restaurant-service:8083",
                "test-secret", circuitBreaker(), routingClient);
        ReflectionTestUtils.setField(service, "etaWindowEnabled", true);

        var preview = service.calculatePreview(validRequest(), 21L);

        verify(routingClient).getEtaWindow(new EtaWindowRequest(
                new Coordinate(10.76, 106.66), new Coordinate(10.78, 106.68), 15));
        assertThat(preview.getEtaMinMinutes()).isEqualTo(27);
        assertThat(preview.getEtaMaxMinutes()).isEqualTo(37);
        assertThat(preview.getEtaSource()).isEqualTo("MAPBOX_DIRECTIONS");
        assertThat(preview.getServiceabilityZoneId()).isNull();
    }

    @Test
    void routingFailureRemainsRetryableWithoutAnEtaFallback() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        when(shippingFeeService.calculateShippingFee(
                10.76, 106.66, 10.78, 106.68, new BigDecimal("100000")))
                .thenReturn(new BigDecimal("15000"));
        RoutingClient routingClient = mock(RoutingClient.class);
        when(routingClient.getEtaWindow(org.mockito.ArgumentMatchers.any(EtaWindowRequest.class)))
                .thenThrow(new RoutingClientException(
                        RoutingClientFailure.UNAVAILABLE, "Routing service is unavailable", null, null));
        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":1,"data":{"restaurantInfo":{
                          "restaurantName":"Quán A","latitude":10.76,"longitude":106.66,
                          "isAvailable":true,"defaultPrepTimeMinutes":15},
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm",
                          "actualPrice":50000,"isAvailable":true,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService, "http://restaurant-service:8083", "test-secret", circuitBreaker(), routingClient);
        ReflectionTestUtils.setField(service, "etaWindowEnabled", true);

        OrderDependencyUnavailableException failure = assertThrows(
                OrderDependencyUnavailableException.class,
                () -> service.calculatePreview(validRequest(), 21L));

        assertThat(failure.getDependency()).isEqualTo("routing-service");
        assertThat(failure.getRetryAfterSeconds()).isEqualTo(30);
        verify(routingClient).getEtaWindow(org.mockito.ArgumentMatchers.any(EtaWindowRequest.class));
    }

    @Test
    void enabledServiceabilityRejectsOutsideZoneBeforeShippingCalculation() {
        ShippingFeeCalculationService shippingFeeService = mock(ShippingFeeCalculationService.class);
        CheckoutPreviewService service = new CheckoutPreviewService(
                internalValidationWebClient("""
                        {"status":1,"data":{"restaurantInfo":{
                          "restaurantName":"Quán A","latitude":10.76,"longitude":106.66,
                          "isAvailable":true,"serviceabilityEnabled":true,"serviceable":false,
                          "serviceabilityReason":"OUTSIDE_ACTIVE_ZONES"},
                          "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                          "isAvailable":true,"hasEnoughStock":true}]}}
                        """),
                shippingFeeService, "http://restaurant-service:8083", "test-secret", circuitBreaker());
        ReflectionTestUtils.setField(service, "serviceabilityEnforcementEnabled", true);

        assertThatThrownBy(() -> service.calculatePreview(validRequest(), 21L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ngoài vùng phục vụ");
        verifyNoInteractions(shippingFeeService);
    }

    private OrderRestaurantCircuitBreaker circuitBreaker() {
        return new OrderRestaurantCircuitBreaker(new RestaurantCallResilienceProperties(), new SimpleMeterRegistry());
    }

    private WebClient internalValidationWebClient(String json) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.method()).isEqualTo(HttpMethod.POST);
                    assertThat(request.url().toString())
                            .isEqualTo("http://restaurant-service:8083/api/restaurants/validate/order");
                    assertThat(request.headers().getFirst("Internal-Token")).isEqualTo("test-secret");
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(json)
                            .build());
                })
                .build();
    }

    private CheckoutPreviewService serviceWithCanonicalMenu(ShippingFeeCalculationService shippingFeeService) {
        return new CheckoutPreviewService(internalValidationWebClient("""
                {"status":1,"data":{
                  "restaurantInfo":{"restaurantName":"Quán A","restaurantAddress":"123 Đường A",
                    "restaurantPhone":"0900000000","latitude":10.76,"longitude":106.66,"creatorId":11,
                    "isAvailable":true},
                  "itemValidations":[{"menuItemId":5,"menuItemName":"Cơm","actualPrice":50000,
                    "isAvailable":true,"hasEnoughStock":true}]}}
                """), shippingFeeService, "http://restaurant-service:8083", "test-secret", circuitBreaker());
    }

    private CheckoutPreviewRequest validRequest() {
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        request.setRestaurantId(7L);
        request.setDeliveryLat(10.78);
        request.setDeliveryLng(106.68);

        CheckoutPreviewRequest.PreviewItem item = new CheckoutPreviewRequest.PreviewItem();
        item.setMenuItemId(5L);
        item.setQuantity(2);
        request.setItems(List.of(item));
        return request;
    }
}
