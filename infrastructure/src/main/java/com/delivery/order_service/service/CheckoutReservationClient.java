package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CheckoutReservationClient {
    private final WebClient webClient;
    private final String promotionUrl;
    private final String flashSaleUrl;
    private final String internalSecret;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Semaphore concurrency = new Semaphore(8, true);

    public CheckoutReservationClient(WebClient webClient,
            @Value("${promotion.service.url:http://promotion-service:8096}") String promotionUrl,
            @Value("${flashsale.service.url:http://flashsale-service:8092}") String flashSaleUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            @Value("${app.checkout.reservation-timeout-ms:2000}") long timeoutMs) {
        this.webClient = webClient; this.promotionUrl = promotionUrl; this.flashSaleUrl = flashSaleUrl;
        this.internalSecret = internalSecret;
        this.timeout = Duration.ofMillis(Math.max(100, Math.min(timeoutMs, 30_000)));
    }

    public VoucherQuote reserveVoucher(UUID reservationId, Long orderId, Long userId, Long voucherId,
            Long restaurantId, BigDecimal subtotal, BigDecimal shippingFee) {
        return reserveVoucher(reservationId, orderId, userId, null, voucherId, restaurantId, subtotal, shippingFee);
    }

    public VoucherQuote reserveVoucher(UUID reservationId, Long orderId, Long userId, Long userPrincipalId, Long voucherId,
            Long restaurantId, BigDecimal subtotal, BigDecimal shippingFee) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reservationId", reservationId); request.put("orderId", orderId); request.put("userId", userId);
        if (userPrincipalId != null) request.put("userPrincipalId", userPrincipalId);
        request.put("voucherId", voucherId); request.put("restaurantId", restaurantId);
        request.put("subtotal", subtotal); request.put("shippingFee", shippingFee);
        Map<String, Object> data = post(promotionUrl + "/api/promotions/internal/reserve", request);
        requireIdentity(data, reservationId, orderId, "voucher");
        if (!"RESERVED".equals(text(data.get("state")))) {
            throw new ValidationException("Voucher không thể được giữ cho đơn hàng");
        }
        try {
            return parseLegacyVoucherQuote(data, "discountAmount");
        } catch (RuntimeException malformed) {
            throw dependencyFailure("promotion-service", "Voucher reservation response is malformed", malformed);
        }
    }

    @SuppressWarnings("unchecked")
    public VoucherQuote quoteVoucher(Long userId, Long voucherId, Long restaurantId,
                                     BigDecimal subtotal, BigDecimal shippingFee) {
        return quoteVoucher(userId, null, voucherId, restaurantId, subtotal, shippingFee);
    }

    public VoucherQuote quoteVoucher(Long userId, Long userPrincipalId, Long voucherId, Long restaurantId,
                                     BigDecimal subtotal, BigDecimal shippingFee) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", restaurantId); request.put("userId", userId);
        if (userPrincipalId != null) request.put("userPrincipalId", userPrincipalId);
        request.put("subTotal", subtotal); request.put("shippingFee", shippingFee);
        request.put("selectedVoucherId", voucherId);
        Map<String, Object> data = requireSuccessfulData(
                requestEnvelope(promotionUrl + "/api/promotions/internal/calculate", request),
                "Voucher quote", "promotion-service");
        try {
            return parseLegacyVoucherQuote(data, "totalDiscount");
        } catch (RuntimeException malformed) {
            throw dependencyFailure("promotion-service", "Voucher quote response is malformed", malformed);
        }
    }

    /** Quotes all wallet vouchers through Promotion's canonical optimizer. */
    public PromotionQuote quoteVouchers(Long userId, Long userPrincipalId, Long restaurantId,
            BigDecimal subtotal, BigDecimal grossShippingFee, List<Long> selectedVoucherIds,
            String selectionMode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", restaurantId);
        request.put("userId", userId);
        if (userPrincipalId != null) request.put("userPrincipalId", userPrincipalId);
        request.put("subTotal", subtotal);
        request.put("shippingFee", grossShippingFee);
        request.put("selectedVoucherIds", selectedVoucherIds == null ? List.of() : selectedVoucherIds);
        if (selectionMode != null) request.put("selectionMode", selectionMode);
        Map<String, Object> data = requireSuccessfulData(
                requestEnvelope(promotionUrl + "/api/promotions/internal/calculate", request),
                "Voucher stacking quote", "promotion-service");
        try {
            return parsePromotionQuote(data, null);
        } catch (OrderDependencyUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException malformed) {
            throw dependencyFailure("promotion-service", "Voucher stacking quote response is malformed", malformed);
        }
    }

    /** Reserves the canonical selected combination atomically. */
    public PromotionQuote reserveVouchers(UUID reservationId, Long orderId, Long userId, Long userPrincipalId,
            Long restaurantId, BigDecimal subtotal, BigDecimal grossShippingFee, List<Long> voucherIds) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reservationId", reservationId);
        request.put("orderId", orderId);
        request.put("userId", userId);
        if (userPrincipalId != null) request.put("userPrincipalId", userPrincipalId);
        request.put("restaurantId", restaurantId);
        request.put("subtotal", subtotal);
        request.put("grossShippingFee", grossShippingFee);
        request.put("voucherIds", voucherIds == null ? List.of() : voucherIds);
        Map<String, Object> data = post(promotionUrl + "/api/promotions/internal/reservations", request);
        requireIdentity(data, reservationId, orderId, "promotion");
        if (!"RESERVED".equals(text(data.get("state")))) {
            throw new ValidationException("Voucher không thể được giữ cho đơn hàng");
        }
        PromotionQuote quote;
        try {
            quote = parsePromotionQuote(data, reservationId);
        } catch (RuntimeException malformed) {
            throw dependencyFailure("promotion-service", "Promotion reservation response is malformed", malformed);
        }
        List<Long> requestedVoucherIds = voucherIds == null ? List.of() : List.copyOf(voucherIds);
        return quote.selectedVoucherIds().isEmpty()
                ? new PromotionQuote(quote.reservationId(), quote.itemDiscount(), quote.shippingDiscount(),
                quote.totalDiscount(), quote.customerShippingFee(), requestedVoucherIds, quote.breakdownJson(),
                quote.breakdown(), quote.platformSubsidy(), quote.shopDiscount())
                : quote;
    }

    public void commitVouchers(UUID reservationId, Long orderId, Long userPrincipalId) {
        transition(promotionUrl + "/api/promotions/internal/promotion-reservations/"
                + reservationId + "/commit?orderId=" + orderId + "&userPrincipalId=" + userPrincipalId);
    }

    public void releaseVouchers(UUID reservationId, Long orderId, Long userPrincipalId) {
        transition(promotionUrl + "/api/promotions/internal/promotion-reservations/"
                + reservationId + "/release?orderId=" + orderId + "&userPrincipalId=" + userPrincipalId);
    }

    public FlashQuote quoteFlash(Long restaurantId, List<CreateOrderRequest.OrderItemRequest> requestItems) {
        List<Map<String, Object>> lines = requestItems.stream().filter(item -> item.getFlashSaleItemId() != null)
                .map(item -> Map.<String, Object>of("flashSaleItemId", item.getFlashSaleItemId(),
                        "quantity", item.getQuantity())).toList();
        Map<String, Object> data = post(flashSaleUrl + "/api/flashsales/internal/quote",
                Map.of("restaurantId", restaurantId, "items", lines));
        try {
            return parseFlashQuote(data, lines.size());
        } catch (OrderDependencyUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException malformed) {
            throw dependencyFailure("flashsale-service", "Flash-sale quote response is malformed", malformed);
        }
    }

    public FlashQuote reserveFlash(UUID reservationId, Long orderId, Long userId, Long restaurantId,
            List<CreateOrderRequest.OrderItemRequest> requestItems) {
        return reserveFlash(reservationId, orderId, userId, null, restaurantId, requestItems);
    }

    public FlashQuote reserveFlash(UUID reservationId, Long orderId, Long userId, Long userPrincipalId, Long restaurantId,
            List<CreateOrderRequest.OrderItemRequest> requestItems) {
        List<Map<String, Object>> lines = requestItems.stream().filter(item -> item.getFlashSaleItemId() != null)
                .map(item -> Map.<String, Object>of("flashSaleItemId", item.getFlashSaleItemId(),
                        "quantity", item.getQuantity())).toList();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reservationId", reservationId); request.put("orderId", orderId); request.put("userId", userId);
        if (userPrincipalId != null) request.put("userPrincipalId", userPrincipalId);
        request.put("restaurantId", restaurantId); request.put("items", lines);
        Map<String, Object> data = post(flashSaleUrl + "/api/flashsales/internal/reserve", request);
        requireIdentity(data, reservationId, orderId, "flash-sale");
        if (!"RESERVED".equals(text(data.get("state")))) {
            throw new ValidationException("Flash-sale item không thể được giữ cho đơn hàng");
        }
        try {
            return parseFlashQuote(data, lines.size());
        } catch (OrderDependencyUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException malformed) {
            throw dependencyFailure("flashsale-service", "Flash-sale reservation response is malformed", malformed);
        }
    }

    private FlashQuote parseFlashQuote(Map<String, Object> data, int expectedSize) {
        Object rawItems = data.get("items");
        if (!(rawItems instanceof List<?> list)) throw new IllegalStateException("Flash-sale response has no items");
        Map<Long, FlashLine> byItem = list.stream().map(this::flashLine).collect(Collectors.toMap(
                FlashLine::flashSaleItemId, Function.identity()));
        if (byItem.size() != expectedSize) throw new IllegalStateException("Flash-sale response item count mismatch");
        return new FlashQuote(byItem);
    }

    public void releaseVoucher(UUID reservationId, Long orderId) {
        transition(promotionUrl + "/api/promotions/internal/reservations/" + reservationId + "/release?orderId=" + orderId);
    }

    public void releaseFlash(UUID reservationId, Long orderId) {
        transition(flashSaleUrl + "/api/flashsales/internal/reservations/" + reservationId
                + "/release?orderId=" + orderId);
    }

    private Map<String, Object> post(String url, Object body) {
        return requireSuccessfulData(requestEnvelope(url, body), "Reservation service", dependencyFor(url));
    }

    private void transition(String url) {
        Map<String, Object> envelope = requestEnvelope(url, null);
        if (!isSuccess(envelope.get("status"))) {
            throw new ValidationException(messageOrDefault(envelope, "Reservation release was rejected"));
        }
    }

    private FlashLine flashLine(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid flash-sale line");
        return new FlashLine(number(map.get("flashSaleItemId")), number(map.get("menuItemId")),
                ((Number) map.get("quantity")).intValue(), decimal(map.get("unitPrice")));
    }

    private void requireIdentity(Map<String, Object> data, UUID reservationId, Long orderId, String type) {
        boolean reservationMatches = reservationId.toString().equals(text(data.get("reservationId")));
        boolean orderMatches;
        try {
            orderMatches = orderId.equals(number(data.get("orderId")));
        } catch (RuntimeException malformed) {
            orderMatches = false;
        }
        if (!reservationMatches || !orderMatches)
            throw dependencyFailure(dependencyForOperation(type), type + " reservation identity mismatch", null);
    }

    private Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(text(value)); }
    private BigDecimal decimal(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(text(value)); }
    private String text(Object value) { return value == null ? null : value.toString(); }
    private void requireSecret() {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new OrderDependencyUnavailableException("checkout-reservation",
                    "Internal reservation credential is missing", null, 30);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requestEnvelope(String url, Object body) {
        requireSecret();
        if (!concurrency.tryAcquire()) {
            throw new OrderDependencyUnavailableException(dependencyFor(url),
                    "Checkout reservation service đang quá tải, vui lòng thử lại", null, 1);
        }
        try {
            var request = webClient.post().uri(url).header("Internal-Token", internalSecret);
            if (body != null) {
                request.bodyValue(body);
            }
            Map<String, Object> envelope = request.retrieve().bodyToMono(Map.class)
                    .timeout(timeout).block();
            if (envelope == null) {
                throw dependencyFailure(dependencyFor(url), "Dependency returned an empty response", null);
            }
            return envelope;
        } catch (ValidationException known) {
            throw known;
        } catch (WebClientResponseException responseException) {
            if (responseException.getStatusCode().is4xxClientError()) {
                throw new ValidationException(dependencyFor(url) + " rejected the reservation request",
                        responseException);
            }
            throw dependencyFailure(dependencyFor(url), "Dependency is temporarily unavailable", responseException);
        } catch (RuntimeException remoteFailure) {
            // Includes response timeouts, connection failures and malformed
            // response decoding. None should be converted into a misleading
            // generic order 400 response.
            throw dependencyFailure(dependencyFor(url), "Dependency is temporarily unavailable", remoteFailure);
        } finally {
            concurrency.release();
        }
    }

    @Value("${app.checkout.reservation-max-concurrent-calls:8}")
    void configureConcurrency(int maxConcurrentCalls) {
        concurrency = new Semaphore(Math.max(1, Math.min(maxConcurrentCalls, 100)), true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireSuccessfulData(Map<String, Object> envelope, String operation,
                                                       String dependency) {
        if (envelope.get("status") == null) {
            throw dependencyFailure(dependency, operation + " response is malformed", null);
        }
        if (!isSuccess(envelope.get("status"))) {
            throw new ValidationException(messageOrDefault(envelope, operation + " was rejected"));
        }
        Object rawData = envelope.get("data");
        if (!(rawData instanceof Map<?, ?> data)) {
            throw dependencyFailure(dependency, operation + " response is malformed", null);
        }
        return (Map<String, Object>) data;
    }

    private boolean isSuccess(Object status) {
        return (status instanceof Number number && number.intValue() == 1)
                || "1".equals(String.valueOf(status));
    }

    private String messageOrDefault(Map<String, Object> envelope, String fallback) {
        Object message = envelope.get("message");
        return message == null || message.toString().isBlank() ? fallback : message.toString();
    }

    private String dependencyFor(String url) {
        if (url != null && url.contains("flashsale")) return "flashsale-service";
        if (url != null && url.contains("promotion")) return "promotion-service";
        return "checkout-reservation";
    }

    private String dependencyForOperation(String operation) {
        return operation != null && operation.toLowerCase(Locale.ROOT).contains("flash")
                ? "flashsale-service" : "promotion-service";
    }

    private OrderDependencyUnavailableException dependencyFailure(String dependency, String message,
                                                                    Throwable cause) {
        return new OrderDependencyUnavailableException(dependency, message, cause);
    }

    public record VoucherQuote(BigDecimal discountAmount,
                               BigDecimal itemDiscount,
                               BigDecimal shippingDiscount,
                               BigDecimal customerShippingFee,
                               BigDecimal grossShippingFee,
                               BigDecimal platformSubsidy,
                               BigDecimal shopDiscount,
                               String breakdownJson,
                               List<Map<String, Object>> breakdown) {
        /** Source-compatible fallback for focused legacy callers. */
        public VoucherQuote(BigDecimal discountAmount) {
            this(discountAmount, discountAmount, BigDecimal.ZERO, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, List.of());
        }
    }
    public record PromotionQuote(UUID reservationId, BigDecimal itemDiscount, BigDecimal shippingDiscount,
                                 BigDecimal totalDiscount, BigDecimal customerShippingFee,
                                 List<Long> selectedVoucherIds, String breakdownJson,
                                 List<Map<String, Object>> breakdown,
                                 BigDecimal platformSubsidy, BigDecimal shopDiscount) {
        public BigDecimal grossShippingFee() {
            return customerShippingFee == null || shippingDiscount == null
                    ? null : customerShippingFee.add(shippingDiscount);
        }
    }
    public record FlashQuote(Map<Long, FlashLine> byFlashSaleItemId) {}
    public record FlashLine(Long flashSaleItemId, Long menuItemId, Integer quantity, BigDecimal unitPrice) {}

    @SuppressWarnings("unchecked")
    private PromotionQuote parsePromotionQuote(Map<String, Object> data, UUID reservationId) {
        Object rawSelected = data.get("selectedVoucherIds");
        List<Long> selected = rawSelected instanceof List<?> list
                ? list.stream().map(this::number).filter(Objects::nonNull).toList() : List.of();
        Object rawLines = data.containsKey("appliedVouchers") ? data.get("appliedVouchers") : data.get("lines");
        List<Map<String, Object>> lines = new ArrayList<>();
        if (rawLines instanceof List<?> list) {
            for (Object raw : list) if (raw instanceof Map<?, ?> map) {
                lines.add(normalizeVoucherLine(map));
            }
        }
        String breakdown;
        try { breakdown = objectMapper.writeValueAsString(lines); }
        catch (Exception e) { throw new IllegalStateException("Promotion breakdown is not serializable", e); }
        BigDecimal platformSubsidy = BigDecimal.ZERO;
        BigDecimal shopDiscount = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal amount = decimal(line.get("discountAmount"));
            if ("SHOP".equalsIgnoreCase(text(line.get("fundingSource")))) {
                shopDiscount = shopDiscount.add(amount);
            } else {
                platformSubsidy = platformSubsidy.add(amount);
            }
        }
        return new PromotionQuote(reservationId,
                decimal(data.get("itemDiscount")), decimal(data.get("shippingDiscount")),
                decimal(data.get("totalDiscount")), decimal(data.get("customerShippingFee")),
                selected, breakdown, lines, platformSubsidy, shopDiscount);
    }

    private VoucherQuote parseLegacyVoucherQuote(Map<String, Object> data, String totalField) {
        BigDecimal total = decimal(data.get(totalField));
        BigDecimal itemDiscount = optionalDecimal(data.get("itemDiscount"));
        BigDecimal shippingDiscount = optionalDecimal(data.get("shippingDiscount"));
        if (itemDiscount == null && shippingDiscount == null) {
            itemDiscount = total;
            shippingDiscount = BigDecimal.ZERO;
        } else {
            itemDiscount = itemDiscount == null ? BigDecimal.ZERO : itemDiscount;
            shippingDiscount = shippingDiscount == null ? BigDecimal.ZERO : shippingDiscount;
        }
        BigDecimal customerShipping = optionalDecimal(data.get("customerShippingFee"));
        BigDecimal grossShipping = optionalDecimal(data.get("grossShippingFee"));
        if (grossShipping == null && customerShipping != null) {
            grossShipping = customerShipping.add(shippingDiscount);
        }

        List<Map<String, Object>> lines = parseAppliedLines(data);
        if (lines.isEmpty()) {
            Long voucherId = optionalLong(data.get("voucherId"));
            Object rawSelected = data.get("selectedVoucherIds");
            if (voucherId == null && rawSelected instanceof List<?> selected && !selected.isEmpty()) {
                voucherId = optionalLong(selected.get(0));
            }
            if (voucherId != null) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("voucherId", voucherId);
                line.put("layer", text(data.get("layer")));
                line.put("fundingSource", text(data.get("fundingSource")));
                line.put("discountBase", optionalDecimal(data.get("discountBase")));
                line.put("discountAmount", total);
                lines = List.of(line);
            }
        }
        String breakdown;
        try {
            breakdown = objectMapper.writeValueAsString(lines);
        } catch (Exception malformed) {
            throw new IllegalStateException("Voucher breakdown is not serializable", malformed);
        }
        BigDecimal platformSubsidy = optionalDecimal(data.get("platformSubsidy"));
        BigDecimal shopDiscount = optionalDecimal(data.get("shopDiscount"));
        if (platformSubsidy == null || shopDiscount == null) {
            platformSubsidy = BigDecimal.ZERO;
            shopDiscount = BigDecimal.ZERO;
            for (Map<String, Object> line : lines) {
                BigDecimal amount = optionalDecimal(line.get("discountAmount"));
                if (amount == null) continue;
                if ("SHOP".equalsIgnoreCase(text(line.get("fundingSource")))) {
                    shopDiscount = shopDiscount.add(amount);
                } else {
                    platformSubsidy = platformSubsidy.add(amount);
                }
            }
        }
        return new VoucherQuote(total, itemDiscount, shippingDiscount, customerShipping, grossShipping,
                platformSubsidy, shopDiscount, breakdown, lines);
    }

    private List<Map<String, Object>> parseAppliedLines(Map<String, Object> data) {
        Object rawLines = data.containsKey("appliedVouchers") ? data.get("appliedVouchers") : data.get("lines");
        List<Map<String, Object>> lines = new ArrayList<>();
        if (rawLines instanceof List<?> list) {
            for (Object raw : list) if (raw instanceof Map<?, ?> map) {
                lines.add(normalizeVoucherLine(map));
            }
        }
        return lines;
    }

    /** Promotion calculate uses id/code; reservation lines use voucherId/voucherCode. */
    private Map<String, Object> normalizeVoucherLine(Map<?, ?> raw) {
        Map<String, Object> line = new LinkedHashMap<>();
        raw.forEach((key, value) -> line.put(String.valueOf(key), value));
        if (!line.containsKey("voucherId") && line.containsKey("id")) {
            line.put("voucherId", line.get("id"));
        }
        if (!line.containsKey("voucherCode") && line.containsKey("code")) {
            line.put("voucherCode", line.get("code"));
        }
        return line;
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null ? null : decimal(value);
    }

    private Long optionalLong(Object value) {
        if (value == null) return null;
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }
}
