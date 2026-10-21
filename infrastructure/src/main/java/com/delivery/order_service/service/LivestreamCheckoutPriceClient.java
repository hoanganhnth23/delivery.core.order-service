package com.delivery.order_service.service;

import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.delivery.order_service.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LivestreamCheckoutPriceClient {
    private final WebClient webClient;
    private final String livestreamServiceUrl;
    private final String internalSecret;
    private final boolean enabled;

    public LivestreamCheckoutPriceClient(
            WebClient webClient,
            @Value("${livestream.service.url:http://livestream-service:8094}") String livestreamServiceUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            @Value("${app.order.livestream-checkout-enabled:false}") boolean enabled) {
        this.webClient = webClient;
        this.livestreamServiceUrl = livestreamServiceUrl;
        this.internalSecret = internalSecret;
        this.enabled = enabled;
    }

    public Map<Long, BigDecimal> resolve(UUID livestreamId, Long restaurantId, List<Long> productIds) {
        validateRequest(livestreamId, restaurantId, productIds);
        if (!enabled) {
            throw new ValidationException("Livestream checkout is disabled");
        }
        if (internalSecret == null || internalSecret.isBlank()) {
            throw unavailable("Livestream internal credential is unavailable", null);
        }

        QuoteEnvelope envelope;
        try {
            envelope = webClient.post()
                    .uri(livestreamServiceUrl + "/api/livestreams/internal/checkout-quote")
                    .header("Internal-Token", internalSecret)
                    .bodyValue(new QuoteRequest(livestreamId, restaurantId, productIds))
                    .retrieve()
                    .bodyToMono(QuoteEnvelope.class)
                    .block(Duration.ofSeconds(3));
        } catch (WebClientResponseException response) {
            if (response.getStatusCode().is4xxClientError()) {
                throw new ValidationException("Giá livestream không còn hiệu lực");
            }
            throw unavailable("Livestream price service is unavailable", response);
        } catch (RuntimeException failure) {
            throw unavailable("Livestream price service is unavailable", failure);
        }
        return validateResponse(envelope, livestreamId, restaurantId, productIds);
    }

    private Map<Long, BigDecimal> validateResponse(QuoteEnvelope envelope, UUID livestreamId,
                                                    Long restaurantId, List<Long> requestedIds) {
        if (envelope == null || envelope.status() == null || envelope.status() != 1
                || envelope.data() == null || !livestreamId.equals(envelope.data().livestreamId())
                || !restaurantId.equals(envelope.data().restaurantId()) || envelope.data().items() == null) {
            throw unavailable("Livestream price response is malformed", null);
        }
        var requested = new LinkedHashSet<>(requestedIds);
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        for (QuoteItem item : envelope.data().items()) {
            if (item == null || item.productId() == null || !requested.contains(item.productId())
                    || item.priceAtLive() == null || item.priceAtLive().signum() <= 0
                    || prices.putIfAbsent(item.productId(), item.priceAtLive()) != null) {
                throw unavailable("Livestream price response is malformed", null);
            }
        }
        return Map.copyOf(prices);
    }

    private void validateRequest(UUID livestreamId, Long restaurantId, List<Long> productIds) {
        if (livestreamId == null || restaurantId == null || restaurantId <= 0
                || productIds == null || productIds.isEmpty() || productIds.size() > 50
                || productIds.stream().anyMatch(id -> id == null || id <= 0)
                || new LinkedHashSet<>(productIds).size() != productIds.size()) {
            throw new ValidationException("Invalid livestream checkout scope");
        }
    }

    private OrderDependencyUnavailableException unavailable(String message, Throwable cause) {
        return new OrderDependencyUnavailableException("livestream-service", message, cause, 3);
    }

    public record QuoteRequest(UUID livestreamId, Long restaurantId, List<Long> productIds) {
    }

    public record QuoteEnvelope(Integer status, QuoteData data, String message) {
    }

    public record QuoteData(UUID livestreamId, Long restaurantId, List<QuoteItem> items) {
    }

    public record QuoteItem(Long productId, BigDecimal priceAtLive) {
    }
}
