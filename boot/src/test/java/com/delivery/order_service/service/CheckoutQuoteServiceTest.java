package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.entity.CheckoutQuote;
import com.delivery.order_service.exception.OrderApiException;
import com.delivery.order_service.repository.CheckoutQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class CheckoutQuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");
    private static final Long PRINCIPAL_ID = 101L;
    private static final Long LEGACY_USER_ID = 202L;

    @Mock private CheckoutQuoteRepository repository;
    @Mock private CheckoutQuoteIssuer issuer;
    @Mock private CheckoutPreviewService previewService;

    private final CheckoutFingerprintService fingerprints = new CheckoutFingerprintService();
    private CheckoutQuoteService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutQuoteService(repository, issuer, previewService, fingerprints,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exactCurrentPriceAcceptsTheQuoteWithoutCreatingAnotherQuote() {
        CreateOrderRequest request = createRequest();
        CheckoutPreviewRequest previewRequest = previewRequest();
        CheckoutPreviewResponse preview = preview("115000");
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest, preview, NOW.plusSeconds(300));
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(previewService.calculatePreview(any(CheckoutPreviewRequest.class), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID)))
                .thenReturn(preview);

        service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID);

        verify(previewService).calculatePreview(any(CheckoutPreviewRequest.class), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID));
        verify(issuer, never()).persist(any(), any(), any());
    }

    @Test
    void changedCurrentPriceReturnsConflictWithFreshQuote() {
        CreateOrderRequest request = createRequest();
        CheckoutPreviewRequest previewRequest = previewRequest();
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest, preview("115000"), NOW.plusSeconds(300));
        CheckoutPreviewResponse repriced = preview("125000");
        CheckoutPreviewResponse freshQuote = CheckoutPreviewResponse.builder()
                .quoteId(UUID.randomUUID())
                .expiresAt(NOW.plusSeconds(300))
                .restaurantId(repriced.getRestaurantId())
                .restaurantName(repriced.getRestaurantName())
                .items(repriced.getItems())
                .subtotal(repriced.getSubtotal())
                .shippingFee(repriced.getShippingFee())
                .discountAmount(repriced.getDiscountAmount())
                .totalPrice(repriced.getTotalPrice())
                .build();
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(previewService.calculatePreview(any(CheckoutPreviewRequest.class), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID)))
                .thenReturn(repriced);
        when(issuer.persist(any(CheckoutPreviewRequest.class), eq(repriced), eq(PRINCIPAL_ID)))
                .thenReturn(freshQuote);

        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("PRICE_CHANGED");
                    assertThat(error.getDetails()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) error.getDetails()).get("quote")).isSameAs(freshQuote);
                });

        verify(issuer).persist(any(CheckoutPreviewRequest.class), eq(repriced), eq(PRINCIPAL_ID));
    }

    @Test
    void expiredQuoteFailsBeforeCallingCanonicalPricing() {
        CreateOrderRequest request = createRequest();
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest(), preview("115000"), NOW);
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("QUOTE_EXPIRED"));

        verifyNoInteractions(previewService, issuer);
    }

    @Test
    void consumeLocksAndLinksTheQuoteToTheCreatedOrder() {
        UUID quoteId = UUID.randomUUID();
        CheckoutQuote quote = quote(quoteId, previewRequest(), preview("115000"), NOW.plusSeconds(300));
        when(repository.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote));

        service.consume(quoteId, PRINCIPAL_ID, 9001L);

        assertThat(quote.getConsumedOrderId()).isEqualTo(9001L);
        verify(repository).findByIdForUpdate(quoteId);
    }

    @Test
    void oneVoucherWithoutModeIsRepricedThroughTheSameLegacyRail() {
        CreateOrderRequest request = createRequest();
        request.setVoucherIds(List.of(55L));
        CheckoutPreviewRequest quotedRequest = previewRequest();
        quotedRequest.setVoucherId(55L);
        CheckoutPreviewResponse preview = preview("95000");
        preview.setVoucherId(55L);
        preview.setSelectedVoucherIds(List.of(55L));
        CheckoutQuote quote = quote(request.getQuoteId(), quotedRequest, preview, NOW.plusSeconds(300));
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(previewService.calculatePreview(any(CheckoutPreviewRequest.class), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID)))
                .thenReturn(preview);

        service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID);

        ArgumentCaptor<CheckoutPreviewRequest> repriced = ArgumentCaptor.forClass(CheckoutPreviewRequest.class);
        verify(previewService).calculatePreview(repriced.capture(), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID));
        assertThat(repriced.getValue().getVoucherId()).isEqualTo(55L);
        assertThat(repriced.getValue().getSelectedVoucherIds()).isNull();
        assertThat(repriced.getValue().getSelectionMode()).isNull();
    }

    @Test
    void repricingPreservesLivestreamSource() {
        CreateOrderRequest request = createRequest();
        request.setLivestreamId(UUID.randomUUID());
        CheckoutPreviewRequest quotedRequest = previewRequest();
        quotedRequest.setLivestreamId(request.getLivestreamId());
        CheckoutPreviewResponse preview = preview("115000");
        CheckoutQuote quote = quote(request.getQuoteId(), quotedRequest, preview, NOW.plusSeconds(300));
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(previewService.calculatePreview(any(CheckoutPreviewRequest.class), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID)))
                .thenReturn(preview);

        service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID);

        ArgumentCaptor<CheckoutPreviewRequest> repriced = ArgumentCaptor.forClass(CheckoutPreviewRequest.class);
        verify(previewService).calculatePreview(repriced.capture(), eq(PRINCIPAL_ID), eq(LEGACY_USER_ID));
        assertThat(repriced.getValue().getLivestreamId()).isEqualTo(request.getLivestreamId());
    }

    @Test
    void missingAndUnknownQuoteKeepApiErrorsAndAvoidPricing() {
        CreateOrderRequest request = createRequest();
        request.setQuoteId(null);
        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("QUOTE_REQUIRED");
                    assertThat(error.getMessage()).isEqualTo("Cần báo giá hợp lệ trước khi đặt đơn");
                });
        verifyNoInteractions(repository, previewService, issuer);
        request.setQuoteId(UUID.randomUUID());
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> assertThat(error.getCode()).isEqualTo("QUOTE_EXPIRED"));
        verifyNoInteractions(previewService, issuer);
    }

    @Test
    void wrongOwnerWinsOverExpiryUsedAndInputButConsumeReportsExpired() {
        CreateOrderRequest request = createRequest();
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest(), preview("115000"), NOW);
        quote.consume(99L);
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(repository.findByIdForUpdate(request.getQuoteId())).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.validateAndReprice(request, 999L, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("QUOTE_MISMATCH");
                    assertThat(error.getMessage()).isEqualTo("Báo giá không thuộc khách hàng hiện tại");
                });
        assertThatThrownBy(() -> service.consume(request.getQuoteId(), 999L, 100L))
                .isInstanceOfSatisfying(OrderApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("QUOTE_EXPIRED");
                    assertThat(error.getMessage()).isEqualTo("Báo giá không còn hiệu lực");
                });
        assertThat(quote.getConsumedOrderId()).isEqualTo(99L);
        verifyNoInteractions(previewService, issuer);
    }

    @Test
    void usedQuoteWinsOverInputMismatchAndRemainsLinkedToOriginalOrder() {
        CreateOrderRequest request = createRequest();
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest(), preview("115000"), NOW.plusSeconds(1));
        quote.consume(99L); request.setDeliveryLat(11.0);
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        when(repository.findByIdForUpdate(request.getQuoteId())).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> assertThat(error.getCode()).isEqualTo("QUOTE_ALREADY_USED"));
        assertThatThrownBy(() -> service.consume(request.getQuoteId(), PRINCIPAL_ID, 100L))
                .isInstanceOfSatisfying(OrderApiException.class, error -> assertThat(error.getCode()).isEqualTo("QUOTE_ALREADY_USED"));
        assertThat(quote.getConsumedOrderId()).isEqualTo(99L);
        verifyNoInteractions(previewService, issuer);
    }

    @Test
    void inputMismatchFailsBeforeRemotePricing() {
        CreateOrderRequest request = createRequest();
        CheckoutQuote quote = quote(request.getQuoteId(), previewRequest(), preview("115000"), NOW.plusSeconds(1));
        request.setDeliveryLat(11.0);
        when(repository.findById(request.getQuoteId())).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.validateAndReprice(request, PRINCIPAL_ID, LEGACY_USER_ID))
                .isInstanceOfSatisfying(OrderApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("QUOTE_MISMATCH");
                    assertThat(error.getMessage()).isEqualTo("Giỏ hàng hoặc địa điểm giao không khớp báo giá");
                });
        verifyNoInteractions(previewService, issuer);
    }

    private CheckoutQuote quote(UUID quoteId, CheckoutPreviewRequest request,
                                CheckoutPreviewResponse response, Instant expiresAt) {
        return new CheckoutQuote(quoteId, PRINCIPAL_ID, fingerprints.pricingInput(request),
                fingerprints.pricingSnapshot(response), expiresAt, NOW);
    }

    private CreateOrderRequest createRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setQuoteId(UUID.randomUUID());
        request.setRestaurantId(7L);
        request.setDeliveryLat(10.78);
        request.setDeliveryLng(106.68);
        CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();
        item.setMenuItemId(11L);
        item.setQuantity(2);
        request.setItems(List.of(item));
        return request;
    }

    private CheckoutPreviewRequest previewRequest() {
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        request.setRestaurantId(7L);
        request.setDeliveryLat(10.78);
        request.setDeliveryLng(106.68);
        CheckoutPreviewRequest.PreviewItem item = new CheckoutPreviewRequest.PreviewItem();
        item.setMenuItemId(11L);
        item.setQuantity(2);
        request.setItems(List.of(item));
        return request;
    }

    private CheckoutPreviewResponse preview(String total) {
        BigDecimal subtotal = new BigDecimal("100000");
        BigDecimal shipping = new BigDecimal(total).subtract(subtotal);
        return CheckoutPreviewResponse.builder()
                .restaurantId(7L)
                .restaurantName("Quán test")
                .items(List.of(CheckoutPreviewResponse.PreviewItemDetail.builder()
                        .menuItemId(11L)
                        .menuItemName("Cơm")
                        .quantity(2)
                        .unitPrice(new BigDecimal("50000"))
                        .lineTotal(subtotal)
                        .build()))
                .subtotal(subtotal)
                .shippingFee(shipping)
                .discountAmount(BigDecimal.ZERO)
                .totalPrice(new BigDecimal(total))
                .build();
    }
}
