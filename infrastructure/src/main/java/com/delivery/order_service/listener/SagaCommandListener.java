package com.delivery.order_service.listener;

import com.delivery.order_service.dto.event.ShipperNotFoundEvent;
import com.delivery.order.contracts.DeliveryStatusUpdatedEvent;
import com.delivery.order.contracts.ShipperEvent;
import com.delivery.order_service.service.SagaOrderCommandProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.delivery.identity.contracts.SimulationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.retry.annotation.Backoff;

/**
 * ✅ Saga Command Listener — Nhận lệnh cập nhật order status từ Saga
 * Orchestrator
 *
 * TRƯỚC: Order-service tự nghe từ delivery-service, match-service
 * SAU: Chỉ nhận lệnh saga.command.update-order-status từ Saga
 *
 * Saga gửi format:
 * { "orderId": 123, "sagaStatus":
 * "SHIPPER_FOUND|SHIPPER_ASSIGNED|PICKED_UP|...", "originalEvent": "{...}" }
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
        retryTopicSuffix = "-retry-order",
        dltTopicSuffix = ".order.DLT")
public class SagaCommandListener {

    private final SagaOrderCommandProcessor commandProcessor;
    private final ObjectMapper objectMapper;

    @Autowired
    public SagaCommandListener(SagaOrderCommandProcessor commandProcessor) {
        this.commandProcessor = commandProcessor;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @KafkaListener(topics = "${app.kafka.input-topics.saga-update-order-status:saga.command.update-order-status}")
    public void handleUpdateOrderStatusCommand(String message, Acknowledgment acknowledgment) {
        try {
            JsonNode json = parseCommandTree(message);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("Saga command must be a JSON object");
            }
            Long orderId = json.has("orderId") ? json.get("orderId").asLong() : null;
            String eventId = json.hasNonNull("eventId") ? json.get("eventId").asText() : null;
            String sagaStatus = json.has("sagaStatus") ? json.get("sagaStatus").asText() : "";
            long orderStatusSequence = json.has("orderStatusSequence")
                    ? json.get("orderStatusSequence").asLong() : 0L;
            String originalEvent = json.has("originalEvent") ? json.get("originalEvent").asText() : "{}";

            log.info("📥 [Order] Saga command: update-order-status — orderId={}, sagaStatus={}",
                    orderId, sagaStatus);

            if (orderId == null || orderId <= 0 || eventId == null) {
                throw new IllegalArgumentException(
                        "Invalid command: stable eventId and positive orderId are required");
            }
            java.util.UUID.fromString(eventId);
            JsonNode originalJson = parseOriginalEventTree(originalEvent);
            requireMatchingOrderIdentity(originalJson, orderId);
            java.util.UUID commandEventId = java.util.UUID.fromString(eventId);
            boolean applied;

            // Delegate thẳng vào service dựa trên sagaStatus
            switch (sagaStatus) {
                // ===== Delivery status updates =====
                case "FINDING_SHIPPER", "WAIT_SHIPPER_CONFIRM",
                        "PICKED_UP", "DELIVERING", "DELIVERED", "CANCELLED" -> {
                    com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent deliveryEvent = deliveryStatusEvent(
                            originalJson, orderId, sagaStatus);
                    applied = orderStatusSequence == 0
                            ? commandProcessor.applyDeliveryStatus(commandEventId, orderId, sagaStatus,
                                    message, deliveryEvent)
                            : commandProcessor.applyDeliveryStatus(commandEventId, orderId, sagaStatus,
                                    message, orderStatusSequence, deliveryEvent);
                }

                // ===== Shipper events =====
                case "SHIPPER_ASSIGNED" -> {
                    ShipperEvent shipperEvent = parseOriginalEvent(originalEvent, ShipperEvent.class);
                    if (shipperEvent != null) {
                        com.delivery.order_service.dto.event.ShipperEvent localShipperEvent = toLocalEvent(shipperEvent, orderId);
                        applied = orderStatusSequence == 0
                                ? commandProcessor.applyShipperAccepted(commandEventId, orderId, sagaStatus,
                                        message, localShipperEvent)
                                : commandProcessor.applyShipperAccepted(commandEventId, orderId, sagaStatus,
                                        message, orderStatusSequence, localShipperEvent);
                    } else {
                        throw new IllegalArgumentException(
                                "SHIPPER_ASSIGNED command requires an originalEvent");
                    }
                }

                case "SHIPPER_FOUND" -> {
                    com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent deliveryEvent = deliveryStatusEvent(
                            originalJson, orderId, "WAIT_SHIPPER_CONFIRM");
                    applied = orderStatusSequence == 0
                            ? commandProcessor.applyDeliveryStatus(commandEventId, orderId, sagaStatus,
                                    message, deliveryEvent)
                            : commandProcessor.applyDeliveryStatus(commandEventId, orderId, sagaStatus,
                                    message, orderStatusSequence, deliveryEvent);
                }

                case "SHIPPER_NOT_FOUND" -> {
                    ShipperNotFoundEvent notFoundEvent = parseOriginalEvent(
                            originalEvent, ShipperNotFoundEvent.class);
                    if (notFoundEvent.getDeliveryId() == null || notFoundEvent.getDeliveryId() <= 0) {
                        throw new IllegalArgumentException(
                                "SHIPPER_NOT_FOUND command requires a positive deliveryId");
                    }
                    notFoundEvent.setOrderId(orderId);
                    applied = orderStatusSequence == 0
                            ? commandProcessor.applyShipperNotFound(commandEventId, orderId, sagaStatus,
                                    message, notFoundEvent)
                            : commandProcessor.applyShipperNotFound(commandEventId, orderId, sagaStatus,
                                    message, orderStatusSequence, notFoundEvent);
                }

                default -> throw new IllegalArgumentException(
                        "Unknown sagaStatus: " + sagaStatus + " for orderId=" + orderId);
            }

            log.info("✅ [Order] {} saga command for orderId={}, sagaStatus={}",
                    applied ? "Processed" : "Skipped exact replay of", orderId, sagaStatus);
            acknowledgment.acknowledge();

        } catch (IllegalArgumentException poison) {
            log.warn("Rejecting poison saga command: {}", poison.getMessage());
            throw poison;
        } catch (Exception e) {
            log.error("💥 [Order] Error processing saga command: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to process saga order command", e);
        }
    }

    private <T> T parseOriginalEvent(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("⚠️ [Order] Could not parse originalEvent into {}: {}", clazz.getSimpleName(), e.getMessage());
            throw new IllegalArgumentException(
                    "Could not parse originalEvent into " + clazz.getSimpleName(), e);
        }
    }

    private JsonNode parseCommandTree(String message) {
        try {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("Saga command must be a JSON object");
            }
            return objectMapper.readTree(message);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse Saga command JSON", e);
        }
    }

    private JsonNode parseOriginalEventTree(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("originalEvent must be a JSON object");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse originalEvent JSON", e);
        }
    }

    private com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent deliveryStatusEvent(
            JsonNode originalEvent, Long orderId, String status) {
        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent(
                optionalPositiveLong(originalEvent, "deliveryId"), orderId,
                optionalPositiveLong(originalEvent, "shipperId"), status, null, status, null,
                null, null, null, optionalText(originalEvent, "notes"), null, null, null, null);
        JsonNode context = originalEvent.get("simulationContext");
        if (context != null && !context.isNull()) {
            try {
                event = new DeliveryStatusUpdatedEvent(
                        event.deliveryId(), event.orderId(), event.shipperId(), event.status(),
                        event.previousStatus(), event.newStatus(), event.oldStatus(), event.updatedAt(),
                        event.timestamp(), event.eventType(), event.notes(), event.currentLat(),
                        event.currentLng(), event.estimatedDeliveryTime(),
                        objectMapper.treeToValue(context, SimulationContext.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("simulationContext must match the canonical contract", ex);
            }
        }
        return toLocalEvent(event);
    }

    private com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent toLocalEvent(
            DeliveryStatusUpdatedEvent event) {
        return new com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent(
                event.deliveryId(), event.orderId(), event.shipperId(), event.status(), event.previousStatus(),
                event.newStatus(), event.oldStatus(), event.updatedAt(), event.timestamp(), event.eventType(),
                event.notes(), event.currentLat(), event.currentLng(), event.estimatedDeliveryTime(),
                event.simulationContext());
    }

    private com.delivery.order_service.dto.event.ShipperEvent toLocalEvent(ShipperEvent event, Long orderId) {
        return new com.delivery.order_service.dto.event.ShipperEvent(
                event.shipperId(), event.deliveryId(), orderId, event.action(), event.notes(), event.rejectReason(),
                event.responseTime(), event.estimatedPickupTime(), event.currentLat(), event.currentLng(),
                event.simulationContext());
    }

    private Long optionalPositiveLong(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer when present");
        }
        return value.longValue();
    }

    private String optionalText(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text when present");
        }
        return value.textValue();
    }

    private void requireMatchingOrderIdentity(JsonNode originalEvent, Long commandOrderId) {
        if (!originalEvent.has("orderId")) {
            return;
        }
        JsonNode originalOrderId = originalEvent.get("orderId");
        if (originalOrderId == null || !originalOrderId.isIntegralNumber()
                || !originalOrderId.canConvertToLong()
                || originalOrderId.longValue() <= 0
                || !commandOrderId.equals(originalOrderId.longValue())) {
            throw new IllegalArgumentException(
                    "originalEvent orderId does not match saga command orderId");
        }
    }
}
