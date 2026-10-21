package com.delivery.order_service.service;

import com.delivery.order_service.dto.internal.ValidatedOrderData;
import com.delivery.order.domain.CheckoutAdmissionPolicy;
import com.delivery.order.domain.CheckoutPricingPolicy;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.config.OrderRestaurantCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * ✅ Service validation cho Order theo Backend Instructions
 * Tích hợp với Restaurant Service để validate restaurant và menu items
 */
@Slf4j
@Service
public class OrderValidationService {

    private final WebClient webClient;
    private final String restaurantServiceUrl;
    private final String internalSecret;
    private final OrderRestaurantCircuitBreaker restaurantCircuitBreaker;
    private final VoucherCheckoutCapability voucherCheckoutCapability;
    @Value("${app.order.voucher-checkout-enabled:false}")
    private boolean voucherCheckoutEnabled;
    @Value("${app.order.flashsale-checkout-enabled:false}")
    private boolean flashSaleCheckoutEnabled;
    @Value("${app.order.livestream-checkout-enabled:false}")
    private boolean livestreamCheckoutEnabled;

    @Autowired
    public OrderValidationService(
            WebClient webClient,
            @Value("${restaurant.service.url}") String restaurantServiceUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            OrderRestaurantCircuitBreaker restaurantCircuitBreaker,
            VoucherCheckoutCapability voucherCheckoutCapability) {
        this.webClient = webClient;
        this.restaurantServiceUrl = restaurantServiceUrl;
        this.internalSecret = internalSecret;
        this.restaurantCircuitBreaker = restaurantCircuitBreaker;
        this.voucherCheckoutCapability = voucherCheckoutCapability;
    }

    /** Source-compatible constructor for focused legacy tests/callers. */
    public OrderValidationService(WebClient webClient, String restaurantServiceUrl,
                                  String internalSecret,
                                  OrderRestaurantCircuitBreaker restaurantCircuitBreaker) {
        this(webClient, restaurantServiceUrl, internalSecret, restaurantCircuitBreaker,
                new VoucherCheckoutCapability(false, ""));
    }

    /**
     * Validate toàn bộ CreateOrderRequest.
     * Trả về ValidatedOrderData chứa thông tin nhà hàng đã được server xác thực.
     * Client KHÔNG cần gửi restaurantName, restaurantAddress, restaurantPhone,
     * pickupLat/Lng.
     */
    public ValidatedOrderData validateCreateOrderRequest(CreateOrderRequest request, Long userId) {
        return validateCreateOrderRequest(request, userId, userId);
    }

    public ValidatedOrderData validateCreateOrderRequest(CreateOrderRequest request, Long principalId, Long userId) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            throw new ValidationException("Dữ liệu đơn hàng không được để trống");
        }

        errors.addAll(CheckoutAdmissionPolicy.errors(admissionFacts(request), userId,
                new CheckoutAdmissionPolicy.Capabilities(voucherCheckoutEnabled, flashSaleCheckoutEnabled,
                        livestreamCheckoutEnabled, () -> voucherCheckoutCapability.isEnabled(principalId))));

        // Không gọi service khác khi request đã sai ngay tại boundary HTTP.
        if (!errors.isEmpty()) {
            throwValidation(userId, errors);
        }

        // 6. Validate với restaurant service — LẦN GỌI DUY NHẤT đến restaurant-service.
        // Thu canonical restaurant data (name, address, phone, lat, lng, creatorId) từ
        // server.
        ValidatedOrderData validatedData = validateWithRestaurantService(request, userId, errors);

        if (!errors.isEmpty()) {
            throwValidation(userId, errors);
        }

        log.info("✅ Order validation passed for user: {}, creatorId: {}", userId,
                validatedData != null ? validatedData.creatorId() : null);
        return validatedData;
    }

    private void throwValidation(Long userId, List<String> errors) {
        String errorMessage = "Dữ liệu đơn hàng không hợp lệ: " + String.join(", ", errors);
        log.error("🚨 Order validation failed for user {}: {}", userId, errorMessage);
        throw new ValidationException(errorMessage);
    }

    private CheckoutAdmissionPolicy.Input admissionFacts(CreateOrderRequest request) {
        return new CheckoutAdmissionPolicy.Input(request.getRestaurantId(), request.getDeliveryAddress(),
                request.getCustomerName(), request.getCustomerPhone(), request.getPaymentMethod(),
                request.getNotes(), request.getVoucherIds(), request.getSelectionMode(),
                request.getItems() == null ? null : request.getItems().stream().map(item -> item == null ? null
                        : new CheckoutAdmissionPolicy.Item(item.getMenuItemId(), item.getQuantity(),
                                item.getNotes(), item.getFlashSaleItemId())).toList(),
                request.getLivestreamId() != null, request.getDeliveryLat(), request.getDeliveryLng());
    }

    /**
     * Validate với restaurant service — LẦN GỌI DUY NHẤT đến restaurant-service
     * trong luồng createOrder.
     * Trả về ValidatedOrderData chứa canonical restaurant data từ server.
     */
    @SuppressWarnings("unchecked")
    private ValidatedOrderData validateWithRestaurantService(CreateOrderRequest request, Long userId,
            List<String> errors) {
        try {
            // Chỉ gửi restaurantId và items — không gửi bất kỳ thông tin nhà hàng nào từ
            // client
            Map<String, Object> orderValidationRequest = Map.of(
                    "restaurantId", request.getRestaurantId(),
                    "items", request.getItems().stream()
                            .map(item -> Map.of(
                                    "menuItemId", item.getMenuItemId(),
                                    "quantity", item.getQuantity()))
                            .toList());

            String url = restaurantServiceUrl + "/api/restaurants/validate/order";

            if (internalSecret == null || internalSecret.isBlank()) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Order/restaurant internal credential chưa được cấu hình", null, 30);
            }

            Map<String, Object> responseBody = restaurantCircuitBreaker.execute(() -> webClient
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("Internal-Token", internalSecret)
                    .bodyValue(orderValidationRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(restaurantCircuitBreaker.timeout())
                    .block());

            if (responseBody == null) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả về response rỗng");
            }

            Integer status = integerValue(responseBody.get("status"));
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");

            log.info("🔍 Order validation for restaurant {}: status={}", request.getRestaurantId(), status);

            if (status == null) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả status không hợp lệ");
            }

            if (data == null) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service trả response không đúng contract");
            }

            // Lấy canonical restaurant data từ server
            Map<String, Object> restaurantInfo = (Map<String, Object>) data.get("restaurantInfo");
            if (restaurantInfo == null) {
                log.warn("⚠️ Restaurant service did not return restaurantInfo for: {}", request.getRestaurantId());
                errors.add("Không tìm thấy thông tin nhà hàng. Restaurant ID: " + request.getRestaurantId());
                return null;
            }

            ValidatedOrderData validatedData = buildValidatedOrderData(restaurantInfo, data, errors);

            // Thu thập errors từ validation items
            if (status != null && status != 1) {
                List<Map<String, Object>> validationErrors = (List<Map<String, Object>>) data.get("errors");
                if (validationErrors != null && !validationErrors.isEmpty()) {
                    for (Map<String, Object> error : validationErrors) {
                        String message = (String) error.get("message");
                        if (message != null)
                            errors.add(message);
                    }
                } else {
                    errors.add("Restaurant/menu item validation thất bại");
                }
            }

            return validatedData;

        } catch (OrderDependencyUnavailableException e) {
            throw e;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                errors.add("Restaurant/menu item validation thất bại");
                return null;
            }
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Restaurant service tạm thời không khả dụng", e);
        } catch (Exception e) {
            log.error("💥 Error validating order with restaurant service: {}", e.getMessage());
            if (hasRemoteFailureCause(e)) {
                throw new OrderDependencyUnavailableException("restaurant-service",
                        "Restaurant service tạm thời không khả dụng", e);
            }
            // A malformed but successfully returned business payload remains a
            // validation failure, preserving the fail-closed catalog contract.
            errors.add("Không thể xác thực thông tin restaurant/menu items");
            return null;
        }
    }

    private boolean hasRemoteFailureCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Build ValidatedOrderData từ restaurantInfo map trả về bởi restaurant-service.
     * Không có side-effect, không chạm vào request.
     */
    @SuppressWarnings("unchecked")
    private ValidatedOrderData buildValidatedOrderData(
            Map<String, Object> restaurantInfo,
            Map<String, Object> validationData,
            List<String> errors) {
        String name = restaurantInfo.get("restaurantName") != null
                ? restaurantInfo.get("restaurantName").toString()
                : (restaurantInfo.get("name") != null ? restaurantInfo.get("name").toString() : null);
        String address = restaurantInfo.get("restaurantAddress") != null
                ? restaurantInfo.get("restaurantAddress").toString()
                : (restaurantInfo.get("address") != null ? restaurantInfo.get("address").toString() : null);
        String phone = restaurantInfo.get("restaurantPhone") != null
                ? restaurantInfo.get("restaurantPhone").toString()
                : (restaurantInfo.get("phone") != null ? restaurantInfo.get("phone").toString() : null);

        Double lat = null;
        if (restaurantInfo.get("latitude") != null) {
            try {
                lat = Double.valueOf(restaurantInfo.get("latitude").toString());
            } catch (NumberFormatException e) {
                log.warn("⚠️ Invalid latitude: {}", restaurantInfo.get("latitude"));
            }
        }

        Double lng = null;
        if (restaurantInfo.get("longitude") != null) {
            try {
                lng = Double.valueOf(restaurantInfo.get("longitude").toString());
            } catch (NumberFormatException e) {
                log.warn("⚠️ Invalid longitude: {}", restaurantInfo.get("longitude"));
            }
        }

        Long creatorId = null;
        if (restaurantInfo.get("creatorId") != null) {
            try {
                creatorId = Long.valueOf(restaurantInfo.get("creatorId").toString());
            } catch (NumberFormatException e) {
                log.warn("⚠️ Invalid creatorId: {}", restaurantInfo.get("creatorId"));
            }
        }

        Long creatorPrincipalId = null;
        if (restaurantInfo.get("ownerPrincipalId") != null) {
            try {
                creatorPrincipalId = Long.valueOf(restaurantInfo.get("ownerPrincipalId").toString());
            } catch (NumberFormatException e) {
                log.warn("⚠️ Invalid ownerPrincipalId: {}", restaurantInfo.get("ownerPrincipalId"));
            }
        }

        errors.addAll(CheckoutPricingPolicy.restaurantErrors(name, address, creatorId, lat, lng));

        List<ValidatedOrderData.ValidatedItemData> items = new ArrayList<>();
        Object rawItems = validationData.get("itemValidations");
        if (rawItems instanceof List<?> itemValidations) {
            for (Object rawItem : itemValidations) {
                if (!(rawItem instanceof Map<?, ?> item)) {
                    errors.add("Restaurant service trả item validation không hợp lệ");
                    continue;
                }
                try {
                    Long menuItemId = Long.valueOf(item.get("menuItemId").toString());
                    String menuItemName = item.get("menuItemName") != null
                            ? item.get("menuItemName").toString() : null;
                    BigDecimal price = item.get("actualPrice") != null
                            ? new BigDecimal(item.get("actualPrice").toString()) : null;
                    if (!CheckoutPricingPolicy.validCanonicalItem(menuItemName, price)) {
                        errors.add("Restaurant service thiếu dữ liệu canonical cho món " + menuItemId);
                    } else {
                        items.add(new ValidatedOrderData.ValidatedItemData(
                                menuItemId, menuItemName, price));
                    }
                } catch (RuntimeException e) {
                    errors.add("Restaurant service trả item validation không hợp lệ");
                }
            }
        } else {
            errors.add("Restaurant service không trả dữ liệu canonical của món ăn");
        }

        log.info("✅ Server-validated restaurant data: name={}, creatorId={}, items={}",
                name, creatorId, items.size());
        return new ValidatedOrderData(creatorId, creatorPrincipalId, name, address, phone, lat, lng,
                List.copyOf(items));
    }

}
