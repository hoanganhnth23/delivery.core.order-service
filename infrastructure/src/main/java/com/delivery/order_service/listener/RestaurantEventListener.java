package com.delivery.order_service.listener;

import com.delivery.order_service.service.OrderEventService;
import com.delivery.order.contracts.RestaurantEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.kafka.support.Acknowledgment;

/**
 * ✅ Restaurant Event Listener theo AI Coding Instructions
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
        // restaurant.order-confirmed is also consumed by Saga. Keep this
        // order-service retry stream isolated from the Saga retry consumer.
        retryTopicSuffix = "-retry-order",
        dltTopicSuffix = ".order.DLT")
public class RestaurantEventListener {

    private final OrderEventService orderEventService;

    // ✅ Constructor Injection (MANDATORY)
    public RestaurantEventListener(OrderEventService orderEventService) {
        this.orderEventService = orderEventService;
    }

    /**
     * ✅ Handle restaurant confirmation events
     */
    @KafkaListener(topics = "${app.kafka.input-topics.restaurant-confirmed:restaurant.order-confirmed}")
    public void handleRestaurantConfirmed(
            @Payload RestaurantEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) String offset,
            Acknowledgment acknowledgment) {

        log.info("📥 Received RestaurantConfirmedEvent: orderId={}, restaurantId={}, estimatedTime={}",
                event.orderId(), event.restaurantId(), event.estimatedPrepTime());

        try {
            requireEventId(event);
            orderEventService.handleRestaurantConfirmed(toLocalEvent(event));
            acknowledgment.acknowledge();
            log.info("✅ Successfully processed RestaurantConfirmedEvent for order: {}", event.orderId());

        } catch (Exception e) {
            log.error("💥 Failed to process RestaurantConfirmedEvent for order {}: {}", 
                    event.orderId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * ✅ Handle restaurant rejection events
     */
    @KafkaListener(topics = "${app.kafka.input-topics.restaurant-rejected:restaurant.order-rejected}")
    public void handleRestaurantRejected(
            @Payload RestaurantEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) String offset,
            Acknowledgment acknowledgment) {

        log.info("📥 Received RestaurantRejectedEvent: orderId={}, restaurantId={}, reason={}",
                event.orderId(), event.restaurantId(), event.rejectionReason());

        try {
            requireEventId(event);
            orderEventService.handleRestaurantRejected(toLocalEvent(event));
            acknowledgment.acknowledge();
            log.info("✅ Successfully processed RestaurantRejectedEvent for order: {}", event.orderId());

        } catch (Exception e) {
            log.error("💥 Failed to process RestaurantRejectedEvent for order {}: {}", 
                    event.orderId(), e.getMessage(), e);
            throw e;
        }
    }

    private void requireEventId(RestaurantEvent event) {
        if (event == null || event.eventId() == null) {
            throw new IllegalArgumentException("restaurant decision eventId is required");
        }
    }

    private com.delivery.order_service.dto.event.RestaurantEvent toLocalEvent(RestaurantEvent event) {
        return new com.delivery.order_service.dto.event.RestaurantEvent(
                event.eventId(), event.restaurantId(), event.actorUserId(), event.orderId(), event.status(),
                event.action(), event.estimatedPrepTime(), event.rejectionReason(), event.processedAt(), event.notes());
    }
}
