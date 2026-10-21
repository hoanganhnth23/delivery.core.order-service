package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.OrderCancelledEvent;
import com.delivery.order.domain.OrderCancellationPolicy;
import com.delivery.order.contracts.OrderCreatedEvent;
import com.delivery.order_service.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ✅ Event Publisher Service cho Order Service theo Backend Instructions
 */
@Slf4j
@Service
public class OrderEventPublisher {

    private static final String ORDER_CREATED_EVENT = "ORDER_CREATED";
    private static final String ORDER_CANCELLED_EVENT = "ORDER_CANCELLED";

    private final OrderOutboxService outboxService;

    @Value("${app.kafka.topics.order-created:order.created}")
    private String orderCreatedTopic;

    @Value("${app.kafka.topics.order-cancelled:order.cancelled}")
    private String orderCancelledTopic;

    @Value("${app.kafka.topics.refund-eligible:order.refund-eligible}")
    private String refundEligibilityTopic;

    public OrderEventPublisher(OrderOutboxService outboxService) {
        this.outboxService = outboxService;
    }
    
    /**
     * Publish OrderCreatedEvent khi order được tạo thành công
     */
    public void publishOrderCreatedEvent(Order order) {
        requirePersistedOrder(order);
        OrderCreatedEvent event = mapOrderToEvent(order);
        outboxService.enqueue(
                ORDER_CREATED_EVENT,
                order.getId().toString(),
                orderCreatedTopic,
                order.getId().toString(),
                event);
        log.info("Queued OrderCreatedEvent in transactional outbox for order {}", order.getId());
    }
    
    /**
     * Publish OrderCancelledEvent khi order bị hủy
     */
    public void publishOrderCancelledEvent(Order order, String previousStatus, Long cancelledBy) {
        publishOrderCancelledEvent(order, previousStatus, cancelledBy, "LEGACY_ACTOR", "ORDER_CANCELLED");
    }

    /**
     * Publish an order cancellation with the source and stable reason code
     * required by the refund eligibility policy.  The free-form reason remains
     * an audit/display field; consumers must branch on the typed code/source.
     */
    public void publishOrderCancelledEvent(Order order, String previousStatus, Long cancelledBy,
                                           String cancelledBySource, String cancelReasonCode) {
        requirePersistedOrder(order);
        OrderCancelledEvent event = mapOrderToCancelledEvent(order, previousStatus, cancelledBy,
                "CANCELLED", cancelledBySource, cancelReasonCode);
        outboxService.enqueue(
                ORDER_CANCELLED_EVENT,
                order.getId().toString(),
                orderCancelledTopic,
                order.getId().toString(),
                event);
        log.info("Queued OrderCancelledEvent in transactional outbox for order {}", order.getId());
    }

    /**
     * A no-shipper terminal outcome is intentionally not represented as an
     * Order cancellation: Order and Delivery remain SHIPPER_NOT_FOUND.  It
     * still needs the same immutable money/reservation snapshot so settlement
     * and checkout compensation can converge without changing fulfilment
     * semantics.
     */
    public void publishRefundEligibilityEvent(Order order, String previousStatus, String reason) {
        requirePersistedOrder(order);
        var intent = OrderCancellationPolicy.noShipperIntent();
        OrderCancelledEvent event = mapOrderToCancelledEvent(order, previousStatus, null,
                intent.currentStatus(), intent.source(), intent.reasonCode());
        event.setCancelReason(OrderCancellationPolicy.noShipperReason(reason));
        outboxService.enqueue(
                "REFUND_ELIGIBLE",
                order.getId().toString(),
                refundEligibilityTopic,
                order.getId().toString(),
                event);
        log.info("Queued refund eligibility event for no-shipper order {}", order.getId());
    }

    private void requirePersistedOrder(Order order) {
        if (order == null || order.getId() == null) {
            throw new IllegalArgumentException("A persisted order is required before enqueueing an event");
        }
    }
    
    /**
     * Map Order entity to OrderCancelledEvent
     */
    private OrderCancelledEvent mapOrderToCancelledEvent(Order order, String previousStatus,
                                                         Long cancelledBy, String currentStatus,
                                                         String cancelledBySource, String cancelReasonCode) {
        // Build cancel event manually to match structure
        OrderCancelledEvent cancelEvent = new OrderCancelledEvent();
        cancelEvent.setOrderId(order.getId());
        cancelEvent.setUserId(order.getUserId());
        cancelEvent.setUserPrincipalId(order.getUserPrincipalId());
        cancelEvent.setRestaurantId(order.getRestaurantId());
        cancelEvent.setPreviousStatus(previousStatus);
        cancelEvent.setCurrentStatus(currentStatus);
        cancelEvent.setCancelReason(order.getCancelReason());
        cancelEvent.setCancelledBy(cancelledBy);
        cancelEvent.setCancelledBySource(cancelledBySource);
        cancelEvent.setCancelReasonCode(cancelReasonCode);
        cancelEvent.setCancelledAt(order.getUpdatedAt() != null ? order.getUpdatedAt() : LocalDateTime.now());
        cancelEvent.setShipperId(order.getShipperId());
        cancelEvent.setHasActiveDelivery(order.getShipperId() != null);
        cancelEvent.setVoucherReservationId(order.getVoucherReservationId());
        cancelEvent.setPromotionReservationId(order.getPromotionReservationId());
        cancelEvent.setFlashSaleReservationId(order.getFlashSaleReservationId());
        cancelEvent.setInventoryReservationId(order.getInventoryReservationId());
        cancelEvent.setSubtotalPrice(order.getSubtotalPrice());
        cancelEvent.setDiscountAmount(order.getDiscountAmount());
        cancelEvent.setShippingFee(order.getShippingFee());
        cancelEvent.setTotalPrice(order.getTotalPrice());
        cancelEvent.setItemDiscount(order.getItemDiscount());
        cancelEvent.setShippingDiscount(order.getShippingDiscount());
        cancelEvent.setCustomerShippingFee(order.getCustomerShippingFee());
        cancelEvent.setGrossShippingFee(order.getGrossShippingFee());
        cancelEvent.setPlatformSubsidy(order.getPlatformSubsidy());
        cancelEvent.setShopDiscount(order.getShopDiscount());
        cancelEvent.setItems(snapshotItems(order));
        cancelEvent.setAppliedVouchers(parseBreakdown(order.getPromotionBreakdown()));
        cancelEvent.setPaymentMethod(order.getPaymentMethod());
        cancelEvent.setCreatedAt(order.getCreatedAt());
        cancelEvent.setUpdatedAt(order.getUpdatedAt());

        return cancelEvent;
    }
    
    /**
     * Map Order entity to OrderCreatedEvent
     */
    private OrderCreatedEvent mapOrderToEvent(Order order) {
        return new OrderCreatedEvent(
                2,
                null,
                order.getId(),
                order.getUserId(),
                order.getUserPrincipalId(),
                order.getRestaurantId(),
                order.getStatus().name(),
                order.getSubtotalPrice(),
                order.getDiscountAmount(),
                order.getShippingFee(),
                order.getTotalPrice(),
                order.getItemDiscount(),
                order.getShippingDiscount(),
                order.getCustomerShippingFee(),
                order.getGrossShippingFee(),
                order.getPlatformSubsidy(),
                order.getShopDiscount(),
                order.getPaymentMethod(),
                order.getDeliveryAddress(),
                order.getDeliveryLat(),
                order.getDeliveryLng(),
                order.getPickupLat(),
                order.getPickupLng(),
                order.getRestaurantName(),
                order.getRestaurantAddress(),
                order.getRestaurantPhone(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                order.getNotes(),
                order.getCreatedAt(),
                order.getCreatorId(),
                order.getCreatorPrincipalId(),
                order.getVoucherReservationId(),
                order.getPromotionReservationId(),
                order.getFlashSaleReservationId(),
                order.getInventoryReservationId(),
                snapshotItems(order),
                parseBreakdown(order.getPromotionBreakdown()),
                order.getSimulationContext(),
                ORDER_CREATED_EVENT,
                order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now());
    }

    /**
     * Copy the persisted order-item snapshot into an additive event field.
     * Downstream analytics must never re-read mutable restaurant/menu prices.
     */
    private java.util.List<java.util.Map<String, Object>> snapshotItems(Order order) {
        if (order.getItems() == null) return java.util.List.of();
        return order.getItems().stream().map(item -> {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("orderItemId", item.getId());
            map.put("menuItemId", item.getMenuItemId());
            map.put("menuItemName", item.getMenuItemName());
            map.put("quantity", item.getQuantity());
            map.put("unitPrice", item.getPrice());
            map.put("lineTotal", item.getPrice() == null || item.getQuantity() == null
                    ? null : item.getPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
            map.put("flashSaleItemId", item.getFlashSaleItemId());
            return map;
        }).toList();
    }

    private java.util.List<java.util.Map<String, Object>> parseBreakdown(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }
}
