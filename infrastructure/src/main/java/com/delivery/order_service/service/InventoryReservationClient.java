package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal Order → Restaurant menu inventory client. */
@Service
public class InventoryReservationClient {

    private final WebClient webClient;
    private final String reservationBaseUrl;
    private final String internalSecret;
    private final Duration timeout;

    @Autowired
    public InventoryReservationClient(
            WebClient webClient,
            @Value("${restaurant.service.url:http://restaurant-service}") String restaurantServiceUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            @Value("${app.checkout.reservation-timeout-ms:2000}") long timeoutMs) {
        this(webClient, restaurantServiceUrl, internalSecret, Duration.ofMillis(timeoutMs));
    }

    /** Source-compatible seam for focused contract tests. */
    public InventoryReservationClient(WebClient webClient, String restaurantServiceUrl,
                                      String internalSecret, Duration timeout) {
        this.webClient = webClient;
        this.reservationBaseUrl = restaurantServiceUrl + "/api/menu-items/internal/inventory";
        this.internalSecret = internalSecret;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(2) : timeout;
    }

    public InventoryReservation reserve(UUID reservationId, Long orderId, Long userId, Long principalId,
                                        Long restaurantId, List<CreateOrderRequest.OrderItemRequest> items) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reservationId", reservationId);
        request.put("orderId", orderId);
        request.put("userId", userId);
        if (principalId != null) request.put("userPrincipalId", principalId);
        request.put("restaurantId", restaurantId);
        request.put("items", items == null ? List.of() : items.stream().map(item -> Map.of(
                "menuItemId", item.getMenuItemId(), "quantity", item.getQuantity())).toList());

        Map<String, Object> data = post(reservationBaseUrl + "/reservations", request);
        InventoryReservation result = parse(data, reservationId, orderId);
        if (!"RESERVED".equals(result.state())) {
            throw new ValidationException("Menu inventory could not be reserved");
        }
        return result;
    }

    public InventoryReservation commit(UUID reservationId, Long orderId) {
        InventoryReservation result = transition(reservationBaseUrl + "/reservations/" + reservationId
                + "/commit?orderId=" + orderId, reservationId, orderId);
        if (!"COMMITTED".equals(result.state())) {
            throw new ValidationException("Menu inventory reservation expired before order creation completed");
        }
        return result;
    }

    public InventoryReservation release(UUID reservationId, Long orderId) {
        return transition(reservationBaseUrl + "/reservations/" + reservationId
                + "/release?orderId=" + orderId, reservationId, orderId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, Object body) {
        Map<String, Object> envelope = request(url, body);
        if (!isSuccess(envelope.get("status"))) {
            throw new ValidationException(message(envelope, "Inventory reservation rejected"));
        }
        Object rawData = envelope.get("data");
        if (!(rawData instanceof Map<?, ?> map)) {
            throw dependencyFailure("restaurant-service", "Inventory response is malformed", null);
        }
        return (Map<String, Object>) map;
    }

    private InventoryReservation transition(String url, UUID reservationId, Long orderId) {
        return parse(post(url, null), reservationId, orderId);
    }

    private InventoryReservation parse(Map<String, Object> data, UUID reservationId, Long orderId) {
        try {
            UUID responseReservationId = UUID.fromString(String.valueOf(data.get("reservationId")));
            Long responseOrderId = number(data.get("orderId"));
            String state = String.valueOf(data.get("state"));
            if (!reservationId.equals(responseReservationId) || !orderId.equals(responseOrderId)
                    || state.isBlank() || "null".equals(state)) {
                throw dependencyFailure("restaurant-service", "Inventory reservation identity mismatch", null);
            }
            return new InventoryReservation(responseReservationId, responseOrderId, state);
        } catch (OrderDependencyUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException malformed) {
            throw dependencyFailure("restaurant-service", "Inventory reservation response is malformed", malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String url, Object body) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Internal inventory credential is missing", null, 30);
        }
        try {
            var request = webClient.post().uri(url).header("Internal-Token", internalSecret);
            if (body != null) request.bodyValue(body);
            Map<String, Object> envelope = request.retrieve().bodyToMono(Map.class)
                    .timeout(timeout).block();
            if (envelope == null) {
                throw dependencyFailure("restaurant-service", "Inventory service returned an empty response", null);
            }
            return envelope;
        } catch (ValidationException known) {
            throw known;
        } catch (WebClientResponseException responseException) {
            if (responseException.getStatusCode().is4xxClientError()) {
                throw new ValidationException("Restaurant inventory rejected the request", responseException);
            }
            throw dependencyFailure("restaurant-service", "Restaurant inventory is temporarily unavailable",
                    responseException);
        } catch (RuntimeException remoteFailure) {
            throw dependencyFailure("restaurant-service", "Restaurant inventory is temporarily unavailable",
                    remoteFailure);
        }
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private boolean isSuccess(Object status) {
        return status instanceof Number number && number.intValue() == 1
                || "1".equals(String.valueOf(status));
    }

    private String message(Map<String, Object> envelope, String fallback) {
        Object message = envelope.get("message");
        return message == null || message.toString().isBlank() ? fallback : message.toString();
    }

    private OrderDependencyUnavailableException dependencyFailure(String dependency, String message,
                                                                   Throwable cause) {
        return new OrderDependencyUnavailableException(dependency, message, cause);
    }

    public record InventoryReservation(UUID reservationId, Long orderId, String state) {
    }
}
