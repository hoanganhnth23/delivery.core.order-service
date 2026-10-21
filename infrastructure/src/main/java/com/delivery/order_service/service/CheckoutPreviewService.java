package com.delivery.order_service.service;

import com.delivery.order.domain.CheckoutPricingPolicy;
import com.delivery.order.domain.CheckoutPreviewPolicy;
import com.delivery.order.domain.CheckoutPreviewPolicy.*;
import static com.delivery.order.domain.CheckoutPreviewPolicy.*;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse.PreviewItemDetail;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse.PriceChangeInfo;
import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.config.OrderRestaurantCircuitBreaker;
import com.delivery.routing.client.RoutingClient;
import com.delivery.routing.contracts.Coordinate;
import com.delivery.routing.contracts.EtaWindowRequest;
import com.delivery.routing.contracts.EtaWindowResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.*;

/**
 * ✅ Service tính toán checkout preview — server là nguồn giá duy nhất.
 * Gọi restaurant-service để lấy giá canonical, tính shipping fee, áp coupon.
 */
@Slf4j
@Service
public class CheckoutPreviewService {

    private final WebClient webClient;
    private final ShippingFeeCalculationService shippingFeeService;
    private final String restaurantServiceUrl;
    private final String internalSecret;
    private final OrderRestaurantCircuitBreaker restaurantCircuitBreaker;
    private final VoucherCheckoutCapability voucherCheckoutCapability;
    private final RoutingClient routingClient;
    @Autowired(required = false)
    private CheckoutReservationClient reservationClient;
    @Autowired(required = false)
    private LivestreamCheckoutPriceClient livestreamPriceClient;
    @Value("${app.order.voucher-checkout-enabled:false}") private boolean voucherCheckoutEnabled;
    @Value("${app.order.flashsale-checkout-enabled:false}") private boolean flashSaleCheckoutEnabled;
    @Value("${app.order.serviceability-enforcement-enabled:false}") private boolean serviceabilityEnforcementEnabled;
    @Value("${app.order.eta-window-enabled:false}") private boolean etaWindowEnabled;

    @Autowired
    public CheckoutPreviewService(WebClient webClient,
                                  ShippingFeeCalculationService shippingFeeService,
                                  @Value("${restaurant.service.url}") String restaurantServiceUrl,
                                  @Value("${app.internal.secret:}") String internalSecret,
                                  OrderRestaurantCircuitBreaker restaurantCircuitBreaker,
                                  VoucherCheckoutCapability voucherCheckoutCapability,
                                  RoutingClient routingClient) {
        this.webClient = webClient;
        this.shippingFeeService = shippingFeeService;
        this.restaurantServiceUrl = restaurantServiceUrl;
        this.internalSecret = internalSecret;
        this.restaurantCircuitBreaker = restaurantCircuitBreaker;
        this.voucherCheckoutCapability = voucherCheckoutCapability;
        this.routingClient = routingClient;
    }

    /** Source-compatible constructor for focused legacy tests/callers. */
    public CheckoutPreviewService(WebClient webClient,
                                  ShippingFeeCalculationService shippingFeeService,
                                  String restaurantServiceUrl,
                                  String internalSecret,
                                  OrderRestaurantCircuitBreaker restaurantCircuitBreaker) {
        this(webClient, shippingFeeService, restaurantServiceUrl, internalSecret,
                restaurantCircuitBreaker, new VoucherCheckoutCapability(false, ""), null);
    }

    /** Source-compatible constructor for focused tests that exercise ETA fetching. */
    public CheckoutPreviewService(WebClient webClient,
                                  ShippingFeeCalculationService shippingFeeService,
                                  String restaurantServiceUrl,
                                  String internalSecret,
                                  OrderRestaurantCircuitBreaker restaurantCircuitBreaker,
                                  RoutingClient routingClient) {
        this(webClient, shippingFeeService, restaurantServiceUrl, internalSecret,
                restaurantCircuitBreaker, new VoucherCheckoutCapability(false, ""), routingClient);
    }

    /** Source-compatible constructor for tests that enable voucher capability explicitly. */
    public CheckoutPreviewService(WebClient webClient,
                                  ShippingFeeCalculationService shippingFeeService,
                                  String restaurantServiceUrl,
                                  String internalSecret,
                                  OrderRestaurantCircuitBreaker restaurantCircuitBreaker,
                                  VoucherCheckoutCapability voucherCheckoutCapability) {
        this(webClient, shippingFeeService, restaurantServiceUrl, internalSecret,
                restaurantCircuitBreaker, voucherCheckoutCapability, null);
    }

    /**
     * Tính toán checkout preview.
     * 1. Gọi restaurant-service lấy menu items + restaurant info
     * 2. Tính subtotal từ giá server
     * 3. Tính shipping fee theo khoảng cách
     * 4. Giữ discount bằng 0 trong COD MVP
     * 5. Trả về breakdown chi tiết
     */
    @SuppressWarnings("unchecked")
    public CheckoutPreviewResponse calculatePreview(CheckoutPreviewRequest request, Long principalId, Long userId) {
        try { return calculatePreviewLocal(request, principalId, userId); }
        catch (CheckoutPreviewPolicy.ValidationException failure) { throw new ValidationException(failure.getMessage()); }
    }

    private CheckoutPreviewResponse calculatePreviewLocal(CheckoutPreviewRequest request, Long principalId, Long userId) {
        CheckoutPreviewPolicy.Admission admission = CheckoutPreviewPolicy.admit(
                request == null ? null : new CheckoutPreviewPolicy.Input(
                        request.getItems() == null ? null : request.getItems().stream().map(item -> item == null ? null
                                : new CheckoutPreviewPolicy.Item(item.getMenuItemId(), item.getQuantity(), item.getFlashSaleItemId())).toList(),
                        request.getCouponCode(), request.getVoucherId(), request.getSelectedVoucherIds(),
                        request.getSelectionMode(), request.getLivestreamId()),
                new CheckoutPreviewPolicy.Capabilities(voucherCheckoutEnabled, flashSaleCheckoutEnabled,
                        reservationClient != null, livestreamPriceClient != null,
                        () -> voucherCheckoutCapability.isEnabled(principalId)));
        boolean hasFlashSale = admission.hasFlashSale();
        boolean hasVoucherSelection = admission.hasVoucherSelection();
        List<Long> selectedVoucherIds = admission.selectedVoucherIds();
        Map<Long, BigDecimal> livestreamPrices = Map.of();
        if (request.getLivestreamId() != null) {
            livestreamPrices = livestreamPriceClient.resolve(request.getLivestreamId(), request.getRestaurantId(),
                    request.getItems().stream().map(CheckoutPreviewRequest.PreviewItem::getMenuItemId).toList());
        }

        // 1. Lấy canonical restaurant + menu item facts qua cùng internal
        // validation contract với create-order. Preview không được gọi public
        // catalog endpoint rồi tự suy luận vì path đó yếu hơn checkout boundary.
        CheckoutPreviewPolicy.Catalog catalog = CheckoutPreviewPolicy.catalog(
                () -> fetchValidatedCheckoutFacts(request), serviceabilityEnforcementEnabled, etaWindowEnabled);
        Map<String, Object> validationData = catalog.data();
        Map<String, Object> restaurantInfo = catalog.restaurant();
        String restaurantName = catalog.name();
        double pickupLat = catalog.pickupLat();
        double pickupLng = catalog.pickupLng();
        int prepMinutes = catalog.prepMinutes();

        Map<Long, ValidatedPreviewItem> menuItemMap = parseValidatedItems(validationData);
        CheckoutReservationClient.FlashQuote flashQuote = hasFlashSale
                ? reservationClient.quoteFlash(request.getRestaurantId(), request.getItems().stream().map(item -> {
                    com.delivery.order_service.dto.request.CreateOrderRequest.OrderItemRequest mapped =
                            new com.delivery.order_service.dto.request.CreateOrderRequest.OrderItemRequest();
                    mapped.setMenuItemId(item.getMenuItemId()); mapped.setFlashSaleItemId(item.getFlashSaleItemId());
                    mapped.setQuantity(item.getQuantity()); return mapped;
                }).toList()) : null;

        // 3. Map từng item trong request → giá server
        List<PreviewItemDetail> previewItems = new ArrayList<>();
        List<PriceChangeInfo> priceChanges = new ArrayList<>();
        List<Long> unavailableIds = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CheckoutPreviewRequest.PreviewItem reqItem : request.getItems()) {
            ValidatedPreviewItem serverItem = menuItemMap.get(reqItem.getMenuItemId());

            if (CheckoutPreviewPolicy.unavailable(serverItem)) {
                unavailableIds.add(reqItem.getMenuItemId());
                continue;
            }

            BigDecimal unitPrice = CheckoutPricingPolicy.regularOrLivestream(reqItem.getMenuItemId(), id -> serverItem.price(), livestreamPrices);
            if (reqItem.getFlashSaleItemId() != null) {
                CheckoutReservationClient.FlashLine line = flashQuote.byFlashSaleItemId()
                        .get(reqItem.getFlashSaleItemId());
                if (!CheckoutPricingPolicy.flashMatches(reqItem.getMenuItemId(), reqItem.getQuantity(),
                        line == null ? null : new CheckoutPricingPolicy.FlashPrice(
                                line.menuItemId(), line.quantity(), line.unitPrice())))
                    throw new ValidationException("Flash-sale quote does not match checkout item");
                unitPrice = line.unitPrice();
            }
            BigDecimal lineTotal = CheckoutPricingPolicy.lineTotal(unitPrice, reqItem.getQuantity());
            subtotal = subtotal.add(lineTotal);

            previewItems.add(PreviewItemDetail.builder()
                    .menuItemId(reqItem.getMenuItemId())
                    .menuItemName(serverItem.name())
                    .imageUrl(null)
                    .unitPrice(unitPrice)
                    .quantity(reqItem.getQuantity())
                    .lineTotal(lineTotal)
                    .build());
        }

        CheckoutPreviewPolicy.requireAvailable(unavailableIds);

        // 4. Tính shipping fee
        BigDecimal shippingFee = shippingFeeService.calculateShippingFee(
                pickupLat, pickupLng,
                request.getDeliveryLat(), request.getDeliveryLng(),
                subtotal);

        EtaWindow etaWindow = etaWindowEnabled
                ? fetchEtaWindow(pickupLat, pickupLng, request.getDeliveryLat(), request.getDeliveryLng(), prepMinutes)
                : null;

        CheckoutReservationClient.PromotionQuote promotionQuote = null;
        CheckoutReservationClient.VoucherQuote legacyQuote = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (request.getVoucherId() != null && request.getSelectedVoucherIds() == null
                && request.getSelectionMode() == null) {
            // Legacy single-voucher quote remains available while old clients
            // drain; it is never mixed with the stacked contract.
            legacyQuote = quoteVoucher(userId, principalId, request.getVoucherId(), request.getRestaurantId(),
                    subtotal, shippingFee);
            discountAmount = legacyQuote.discountAmount();
        } else if (hasVoucherSelection) {
            promotionQuote = reservationClient.quoteVouchers(userId, principalId, request.getRestaurantId(),
                    subtotal, shippingFee, selectedVoucherIds, request.getSelectionMode());
            discountAmount = promotionQuote.totalDiscount();
        }
        if (!CheckoutPricingPolicy.validDiscount(discountAmount, subtotal, shippingFee))
            throw new ValidationException("Voucher quote returned an invalid discount");

        BigDecimal itemDiscount = promotionQuote != null ? promotionQuote.itemDiscount()
                : legacyQuote != null ? legacyQuote.itemDiscount() : discountAmount;
        BigDecimal shippingDiscount = promotionQuote != null ? promotionQuote.shippingDiscount()
                : legacyQuote != null ? legacyQuote.shippingDiscount() : BigDecimal.ZERO;
        BigDecimal customerShippingFee = promotionQuote != null
                ? promotionQuote.customerShippingFee()
                : CheckoutPricingPolicy.customerShipping(shippingFee, shippingDiscount,
                        legacyQuote == null ? null : legacyQuote.customerShippingFee());
        BigDecimal grossShippingFee = shippingFee;
        BigDecimal platformSubsidy = promotionQuote != null ? promotionQuote.platformSubsidy()
                : legacyQuote != null ? legacyQuote.platformSubsidy() : BigDecimal.ZERO;
        BigDecimal shopDiscount = promotionQuote != null ? promotionQuote.shopDiscount()
                : legacyQuote != null ? legacyQuote.shopDiscount() : BigDecimal.ZERO;
        BigDecimal totalPrice = CheckoutPricingPolicy.total(subtotal, itemDiscount, customerShippingFee);
        if (!CheckoutPricingPolicy.positivePayableFood(totalPrice, grossShippingFee)) {
            throw new ValidationException("Voucher phải để lại số tiền món dương cho đơn hàng");
        }

        log.info("✅ Checkout preview: subtotal={}, shipping={}, discount={}, total={}, items={}, unavailable={}",
                subtotal, shippingFee, discountAmount, totalPrice, previewItems.size(), unavailableIds.size());

        return CheckoutPreviewResponse.builder()
                .restaurantId(request.getRestaurantId())
                .restaurantName(restaurantName)
                .etaMinMinutes(etaWindow == null ? null : etaWindow.minMinutes())
                .etaMaxMinutes(etaWindow == null ? null : etaWindow.maxMinutes())
                .etaSource(etaWindow == null ? null : etaWindow.source())
                .serviceabilityZoneId(serviceabilityEnforcementEnabled
                        ? asLong(restaurantInfo.get("serviceabilityZoneId")) : null)
                .serviceabilityZoneRevision(serviceabilityEnforcementEnabled
                        ? asLong(restaurantInfo.get("serviceabilityZoneRevision")) : null)
                .items(previewItems)
                .subtotal(subtotal)
                .shippingFee(shippingFee)
                .discountAmount(discountAmount)
                .totalPrice(totalPrice)
                .itemDiscount(itemDiscount)
                .shippingDiscount(shippingDiscount)
                .customerShippingFee(customerShippingFee)
                .grossShippingFee(grossShippingFee)
                .platformSubsidy(platformSubsidy)
                .shopDiscount(shopDiscount)
                .couponCode(request.getCouponCode())
                .couponMessage(null)
                .voucherId(request.getVoucherId())
                .selectedVoucherIds(promotionQuote == null ? selectedVoucherIds : promotionQuote.selectedVoucherIds())
                .selectionMode(request.getSelectionMode())
                .appliedVouchers(promotionQuote != null
                        ? toAppliedVouchers(promotionQuote)
                        : toAppliedVouchers(legacyQuote))
                .priceChanges(priceChanges)
                .unavailableItemIds(unavailableIds)
                .build();
    }

    /** Compatibility rail for existing callers/tests while JWT subject is still legacy profile ID. */
    public CheckoutPreviewResponse calculatePreview(CheckoutPreviewRequest request, Long userId) {
        return calculatePreview(request, userId, userId);
    }

    /**
     * Keep the legacy internal reservation contract available while identity
     * migration is in compatibility mode. Once principal and legacy IDs
     * differ, include the stable principal in the reservation request.
     */
    private CheckoutReservationClient.VoucherQuote quoteVoucher(
            Long userId, Long principalId, Long voucherId, Long restaurantId,
            BigDecimal subtotal, BigDecimal shippingFee) {
        if (principalId == null || java.util.Objects.equals(principalId, userId)) {
            return reservationClient.quoteVoucher(userId, voucherId, restaurantId, subtotal, shippingFee);
        }
        return reservationClient.quoteVoucher(userId, principalId, voucherId, restaurantId, subtotal, shippingFee);
    }

    private EtaWindow fetchEtaWindow(double pickupLat, double pickupLng,
                                     double deliveryLat, double deliveryLng,
                                     int prepMinutes) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new OrderDependencyUnavailableException("routing-service",
                    "Order/routing internal credential chưa được cấu hình", null, 30);
        }
        try {
            return CheckoutPreviewPolicy.eta(() -> {
                EtaWindowResponse response = routingClient.getEtaWindow(new EtaWindowRequest(
                        new Coordinate(pickupLat, pickupLng), new Coordinate(deliveryLat, deliveryLng), prepMinutes));
                return response == null ? null : new EtaWindow(response.minMinutes(), response.maxMinutes(), response.source());
            });
        } catch (Exception failure) {
            if (failure instanceof OrderDependencyUnavailableException dependency) throw dependency;
            throw new OrderDependencyUnavailableException("routing-service",
                    "Routing service tạm thời không khả dụng", failure, 30);
        }
    }

    private List<CheckoutPreviewResponse.AppliedVoucherInfo> toAppliedVouchers(
            CheckoutReservationClient.PromotionQuote quote) {
        if (quote == null || quote.breakdownJson() == null) return List.of();
        return quote.breakdown().stream().map(line -> CheckoutPreviewResponse.AppliedVoucherInfo.builder()
                .voucherId(asLong(line.get("voucherId")))
                .code(line.get("voucherCode") == null ? text(line.get("code")) : text(line.get("voucherCode")))
                .layer(text(line.get("layer")))
                .fundingSource(text(line.get("fundingSource")))
                .discountBase(asDecimal(line.get("discountBase")))
                .discountAmount(asDecimal(line.get("discountAmount")))
                .build()).toList();
    }

    private List<CheckoutPreviewResponse.AppliedVoucherInfo> toAppliedVouchers(
            CheckoutReservationClient.VoucherQuote quote) {
        if (quote == null || quote.breakdown() == null) return List.of();
        return quote.breakdown().stream().map(line -> CheckoutPreviewResponse.AppliedVoucherInfo.builder()
                .voucherId(asLong(line.get("voucherId")))
                .code(line.get("voucherCode") == null ? text(line.get("code")) : text(line.get("voucherCode")))
                .layer(text(line.get("layer")))
                .fundingSource(text(line.get("fundingSource")))
                .discountBase(asDecimal(line.get("discountBase")))
                .discountAmount(asDecimal(line.get("discountAmount")))
                .build()).toList();
    }

    private String text(Object value) { return value == null ? null : value.toString(); }
    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        return value == null ? null : Long.valueOf(value.toString());
    }
    private BigDecimal asDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : value == null ? null : new BigDecimal(value.toString());
    }

    // ────────────────── Private helpers ──────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchValidatedCheckoutFacts(CheckoutPreviewRequest request) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Order/restaurant internal credential chưa được cấu hình", null, 30);
        }

        try {
            Map<String, Object> orderValidationRequest = Map.of(
                    "restaurantId", request.getRestaurantId(),
                    "deliveryLat", request.getDeliveryLat(),
                    "deliveryLng", request.getDeliveryLng(),
                    "items", request.getItems().stream()
                            .map(item -> Map.of(
                                    "menuItemId", item.getMenuItemId(),
                                    "quantity", item.getQuantity()))
                            .toList());

            Map<String, Object> response = restaurantCircuitBreaker.execute(() -> webClient
                    .post()
                    .uri(restaurantServiceUrl + "/api/restaurants/validate/order")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Internal-Token", internalSecret)
                    .bodyValue(orderValidationRequest)
                    .retrieve().bodyToMono(Map.class)
                    .timeout(restaurantCircuitBreaker.timeout())
                    .block());

            if (response == null) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả về response rỗng");
            }
            Object rawData = response.get("data");
            if (!(rawData instanceof Map<?, ?>)) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả response không đúng contract");
            }
            Map<String, Object> data = requireMap(rawData,
                    "Response từ restaurant service không hợp lệ");
            Integer status = getIntegerValue(response.get("status"));
            if (status == null) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả status không hợp lệ");
            }
            CheckoutPreviewPolicy.requireCatalogAccepted(status, data);

            return data;
        } catch (Exception e) {
            if (e instanceof CheckoutPreviewPolicy.ValidationException failure) {
                throw new ValidationException(failure.getMessage());
            }
            if (e instanceof ValidationException validationException) {
                throw validationException;
            }
            if (e instanceof OrderDependencyUnavailableException dependencyUnavailable) {
                throw dependencyUnavailable;
            }
            if (e instanceof WebClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError()) {
                throw new ValidationException("Không thể xác thực thông tin restaurant/menu items");
            }
            log.error("❌ Failed to validate checkout for restaurant {}: {}",
                    request.getRestaurantId(), e.getMessage());
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Restaurant service tạm thời không khả dụng", e);
        }
    }

}
