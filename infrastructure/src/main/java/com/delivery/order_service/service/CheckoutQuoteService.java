package com.delivery.order_service.service;

import com.delivery.order.domain.CheckoutQuotePolicy;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.entity.CheckoutQuote;
import com.delivery.order_service.exception.OrderApiException;
import com.delivery.order_service.repository.CheckoutQuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates the customer-confirmed quote against current canonical pricing. */
@Service
public class CheckoutQuoteService {
    private final CheckoutQuoteRepository repository;
    private final CheckoutQuoteIssuer issuer;
    private final CheckoutPreviewService previewService;
    private final CheckoutFingerprintService fingerprints;
    private final Clock clock;

    public CheckoutQuoteService(CheckoutQuoteRepository repository, CheckoutQuoteIssuer issuer,
                                CheckoutPreviewService previewService,
                                CheckoutFingerprintService fingerprints, Clock clock) {
        this.repository = repository;
        this.issuer = issuer;
        this.previewService = previewService;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    public CheckoutPreviewResponse issue(CheckoutPreviewRequest request, Long principalId, Long userId) {
        return issuer.issue(request, principalId, userId);
    }

    /**
     * Validates a quote without holding an Order DB transaction across the
     * remote restaurant/Promotion calls. The final {@link #consume} call is
     * still the locking authority inside the create-order write transaction.
     */
    public CheckoutPreviewResponse validateAndReprice(CreateOrderRequest request, Long principalId, Long userId) {
        return com.delivery.order.application.QuoteWorkflow.validateAndReprice(
                ports(request, request.getQuoteId(), principalId, userId, null));
    }

    @Transactional
    public void consume(UUID quoteId, Long principalId, Long orderId) {
        com.delivery.order.application.QuoteWorkflow.consume(ports(null, quoteId, principalId, null, orderId));
    }

    private com.delivery.order.application.api.QuotePorts<CheckoutQuote, CheckoutPreviewRequest, CheckoutPreviewResponse> ports(
            CreateOrderRequest request, UUID quoteId, Long principalId, Long userId, Long orderId) {
        return new com.delivery.order.application.api.QuotePorts<>() {
            public CheckoutQuote find(boolean lock) { return repositoryQuote(quoteId, lock); }
            public void validate(CheckoutQuote quote) {
                decide(() -> CheckoutQuotePolicy.validate(facts(quote), principalId, clock::instant,
                        () -> fingerprints.pricingInput(request)));
            }
            public CheckoutPreviewRequest previewInput() { return toPreviewRequest(request); }
            public CheckoutPreviewResponse reprice(CheckoutPreviewRequest input) {
                return previewService.calculatePreview(input, principalId, userId);
            }
            public boolean priceChanged(CheckoutQuote quote, CheckoutPreviewResponse current) {
                return CheckoutQuotePolicy.priceChanged(facts(quote), () -> fingerprints.pricingSnapshot(current));
            }
            public CheckoutPreviewResponse replacement(CheckoutPreviewRequest input, CheckoutPreviewResponse current) {
                return issuer.persist(input, current, principalId);
            }
            public RuntimeException changed(CheckoutPreviewResponse replacement) {
                return new OrderApiException("PRICE_CHANGED", "Giá đơn hàng đã thay đổi, vui lòng xác nhận lại",
                        Map.of("quote", replacement));
            }
            public void admitConsume(CheckoutQuote quote) {
                decide(() -> CheckoutQuotePolicy.consume(facts(quote), principalId, clock::instant));
            }
            public void consume(CheckoutQuote quote) { quote.consume(orderId); }
        };
    }

    private CheckoutPreviewRequest toPreviewRequest(CreateOrderRequest request) {
        CheckoutPreviewRequest preview = new CheckoutPreviewRequest();
        preview.setLivestreamId(request.getLivestreamId());
        preview.setRestaurantId(request.getRestaurantId());
        preview.setDeliveryLat(request.getDeliveryLat());
        preview.setDeliveryLng(request.getDeliveryLng());
        CheckoutQuotePolicy.VoucherSelection selection = CheckoutQuotePolicy.previewSelection(
                request.getVoucherIds(), request.getSelectionMode());
        preview.setVoucherId(selection.voucherId());
        if (selection.includeSelectedIds()) preview.setSelectedVoucherIds(request.getVoucherIds());
        preview.setSelectionMode(request.getSelectionMode());
        preview.setItems((request.getItems() == null ? List.<CreateOrderRequest.OrderItemRequest>of() : request.getItems())
                .stream().map(item -> {
                    CheckoutPreviewRequest.PreviewItem mapped = new CheckoutPreviewRequest.PreviewItem();
                    mapped.setMenuItemId(item.getMenuItemId());
                    mapped.setQuantity(item.getQuantity());
                    mapped.setFlashSaleItemId(item.getFlashSaleItemId());
                    return mapped;
                }).toList());
        return preview;
    }

    private CheckoutQuote repositoryQuote(UUID id, boolean lock) {
        if (!lock) decide(() -> CheckoutQuotePolicy.requireId(id));
        CheckoutQuote quote = (lock ? repository.findByIdForUpdate(id) : repository.findById(id)).orElse(null);
        decide(() -> CheckoutQuotePolicy.requireFound(quote == null ? null : facts(quote)));
        return quote;
    }
    private CheckoutQuotePolicy.Quote facts(CheckoutQuote quote) {
        return new CheckoutQuotePolicy.Quote(quote.getPrincipalId(), quote.getExpiresAt(), quote.getConsumedOrderId(),
                quote.getPricingInputFingerprint(), quote.getPricingFingerprint());
    }
    private void decide(Runnable decision) {
        try { decision.run(); }
        catch (CheckoutQuotePolicy.Rejected failure) {
            throw new OrderApiException(failure.code(), failure.getMessage());
        }
    }
}
