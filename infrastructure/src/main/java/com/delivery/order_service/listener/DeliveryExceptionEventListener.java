package com.delivery.order_service.listener;

import com.delivery.order_service.service.OrderEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Delivery publishes every post-pickup exception change on one topic. Order only
 * reacts to the final RETURNED (restaurant confirmed the food is back): the
 * customer then sees the order as cancelled instead of "delivering" forever.
 */
@Slf4j
@Component
@RetryableTopic(
        attempts = "${app.kafka.retry.attempts:4}",
        backoff = @Backoff(delayExpression = "${app.kafka.retry.initial-delay-ms:1000}",
                multiplierExpression = "${app.kafka.retry.multiplier:2.0}",
                maxDelayExpression = "${app.kafka.retry.max-delay-ms:10000}"),
        exclude = IllegalArgumentException.class,
        autoCreateTopics = "${app.kafka.retry.auto-create-topics:false}",
        // delivery.exception.reported is also consumed by Settlement.
        retryTopicSuffix = "-retry-order",
        dltTopicSuffix = ".order.DLT")
public class DeliveryExceptionEventListener {

    private final OrderEventService orderEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeliveryExceptionEventListener(OrderEventService orderEventService) {
        this.orderEventService = orderEventService;
    }

    @KafkaListener(topics = "${app.kafka.input-topics.delivery-exception:delivery.exception.reported}")
    public void handleDeliveryException(String message, Acknowledgment acknowledgment) {
        JsonNode event;
        try {
            event = objectMapper.readTree(message);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Invalid delivery exception JSON", malformed);
        }
        if (event == null || !event.isObject() || !event.path("orderId").canConvertToLong()
                || event.path("orderId").asLong() <= 0) {
            throw new IllegalArgumentException("Delivery exception event needs a positive orderId");
        }
        long orderId = event.path("orderId").asLong();
        if ("RETURNED".equals(event.path("exceptionStatus").asText())) {
            boolean applied = orderEventService.handleDeliveryReturned(orderId,
                    event.path("reason").isTextual() ? event.path("reason").asText() : null);
            log.info("Delivery exception RETURNED for order {} -> {}", orderId, applied ? "cancelled" : "no change");
        }
        acknowledgment.acknowledge();
    }
}
