package com.delivery.order_service.service;

import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Asks Delivery before a cancellation is committed. Order's status is a copy of
 * Delivery's and can lag behind a pickup; Delivery decides under its row lock
 * and holds the pickup until order.cancelled arrives. Fails closed: when
 * Delivery cannot answer, the cancel is refused as retryable instead of guessed.
 */
@Service
public class DeliveryCancellationGate {

    static final String DEFAULT_REFUSAL = "Shipper đã lấy hàng, không thể hủy đơn";

    private final WebClient webClient;
    private final String deliveryUrl;
    private final String internalSecret;
    private final Duration timeout;
    private final boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeliveryCancellationGate(WebClient webClient,
            @Value("${delivery.service.url:http://delivery-service:8085}") String deliveryUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            @Value("${app.cancel.delivery-gate-timeout-ms:2000}") long timeoutMs,
            @Value("${app.cancel.delivery-gate-enabled:true}") boolean enabled) {
        this.webClient = webClient;
        this.deliveryUrl = deliveryUrl;
        this.internalSecret = internalSecret;
        this.timeout = Duration.ofMillis(Math.max(100, Math.min(timeoutMs, 10_000)));
        this.enabled = enabled;
    }

    private record Reply(int status, String body) { }

    /** Returns when the order may be cancelled; throws otherwise. */
    public void requireCancellable(Long orderId) {
        if (!enabled) return;
        Reply reply;
        try {
            reply = webClient.post()
                    .uri(deliveryUrl + "/api/deliveries/internal/orders/{orderId}/cancellation-hold", orderId)
                    .header("Internal-Token", internalSecret)
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new Reply(response.statusCode().value(), body)))
                    .block(timeout);
        } catch (RuntimeException unavailable) {
            throw new OrderDependencyUnavailableException("delivery-service",
                    "Delivery cancellation check failed", unavailable);
        }
        if (reply == null) {
            throw new OrderDependencyUnavailableException("delivery-service", "Delivery cancellation check timed out");
        }
        if (reply.status() == 200) return;
        if (reply.status() == 409) throw new IllegalStateException(refusal(reply.body()));
        throw new OrderDependencyUnavailableException("delivery-service",
                "Delivery cancellation check answered HTTP " + reply.status());
    }

    private String refusal(String body) {
        try {
            JsonNode message = objectMapper.readTree(body).path("message");
            return message.isTextual() && !message.asText().isBlank() ? message.asText() : DEFAULT_REFUSAL;
        } catch (Exception malformed) {
            return DEFAULT_REFUSAL;
        }
    }
}
