package com.delivery.order_service.service.impl;

import com.delivery.order.domain.RestaurantPaymentPolicy;
import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.dto.event.PaymentEvent;
import com.delivery.order_service.dto.event.RestaurantEvent;
import com.delivery.order_service.dto.event.ShipperEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.entity.RestaurantDecisionReceipt;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.RestaurantDecisionReceiptRepository;
import com.delivery.order_service.service.OrderEventPublisher;
import com.delivery.order_service.service.OrderEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * ✅ Order Event Service Implementation theo AI Coding Instructions
 */
@Slf4j
@Service
public class OrderEventServiceImpl implements OrderEventService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final RestaurantDecisionReceiptRepository restaurantDecisionReceiptRepository;
    private final ObjectMapper objectMapper;

    // ✅ Constructor Injection (MANDATORY)
    public OrderEventServiceImpl(
            OrderRepository orderRepository,
            OrderEventPublisher orderEventPublisher,
            RestaurantDecisionReceiptRepository restaurantDecisionReceiptRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.restaurantDecisionReceiptRepository = restaurantDecisionReceiptRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void handleDeliveryStatusUpdate(DeliveryStatusUpdatedEvent event) {
        log.info("🚚 Processing delivery status update: orderId={}, deliveryId={}, status={}", 
                event.getOrderId(), event.getDeliveryId(), event.getStatus());

        // ✅ Sử dụng orderId từ event để tìm và cập nhật order
        Order order = findOrderById(event.getOrderId());

        com.delivery.identity.contracts.SimulationContext expected =
                com.delivery.identity.contracts.SimulationContext.orReal(order.getSimulationContext());
        com.delivery.identity.contracts.SimulationContext actual =
                com.delivery.identity.contracts.SimulationContext.orReal(event.getSimulationContext());
        expected.requireValid();
        actual.requireValid();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("delivery status execution context does not match order");
        }

        // Map delivery status to order status
        OrderStatus newOrderStatus = mapDeliveryStatusToOrderStatus(event.getStatus());
        
        if (!newOrderStatus.equals(order.getStatus())) {
            OrderStatus previousStatus = order.getStatus();
            for (com.delivery.order.domain.OrderStatus step :
                    com.delivery.order.domain.SagaStatusPolicy.deliveryTransitions(
                            order.getStatus().toDomain(), newOrderStatus.toDomain())) {
                transition(order, OrderStatus.valueOf(step.name()));
            }
            if (newOrderStatus == OrderStatus.FINDING_SHIPPER) {
                order.setShipperId(null);
            }
            order.setUpdatedAt(LocalDateTime.now());

            // Add notes if available
            if (event.getNotes() != null) {
                appendNotes(order, event.getNotes());
            }

            orderRepository.save(order);
            
            log.info("✅ Updated order {} status: {} -> {}", 
                    order.getId(), previousStatus, newOrderStatus);
        }
    }

    @Override
    @Transactional
    public void handlePaymentCompleted(PaymentEvent event) {
        log.info("💳 Processing payment completed: orderId={}, amount={}", 
                event.getOrderId(), event.getAmount());

        Order order = findOrderById(event.getOrderId());

        if (RestaurantPaymentPolicy.paymentCompletionChangesState(
                order.getPaymentMethod(), order.getStatus() == null ? null : order.getStatus().toDomain())) {
            transition(order, OrderStatus.CONFIRMED);
            order.setUpdatedAt(LocalDateTime.now());
            appendNotes(order, "Payment completed: " + event.getAmount());

            orderRepository.save(order);
            
            log.info("✅ Order {} marked as CONFIRMED after payment", order.getId());
        }
    }

    @Override
    @Transactional
    public void handlePaymentFailed(PaymentEvent event) {
        log.info("💳 Processing payment failed: orderId={}, reason={}", 
                event.getOrderId(), event.getFailureReason());

        Order order = findOrderById(event.getOrderId());

        if (RestaurantPaymentPolicy.ignoresPaymentFailure(order.getPaymentMethod())) {
            log.warn("Ignoring payment failure for COD order {}", order.getId());
            return;
        }
        String previousStatus = order.getStatus().name();
        transition(order, OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        order.setCancelReason(RestaurantPaymentPolicy.paymentFailureReason(event.getFailureReason()));
        order.setCancelledBy(order.getUserId());
        if (event.getFailureReason() != null) {
            appendNotes(order, "Payment failed: " + event.getFailureReason());
        }

        orderRepository.save(order);
        orderEventPublisher.publishOrderCancelledEvent(order, previousStatus, order.getUserId(),
                "SYSTEM", "PAYMENT_FAILED");
        
        log.info("✅ Order {} marked as PAYMENT_FAILED", order.getId());
    }

    @Override
    @Transactional
    public void handleRestaurantConfirmed(RestaurantEvent event) {
        log.info("🏪 Processing restaurant confirmation: orderId={}, estimatedTime={}", 
                event.getOrderId(), event.getEstimatedPrepTime());

        Order order = findOrderById(event.getOrderId());

        validateRestaurantEvent(order, event);
        if (registerDecisionReceiptOrIdentifyExactReplay(event, "CONFIRMED")) {
            log.info("Restaurant confirmation event {} already applied for order {}, skipping exact replay",
                    event.getEventId(), order.getId());
            return;
        }
        if (!RestaurantPaymentPolicy.restaurantConfirmationChangesState(order.getStatus().toDomain())) {
            log.info("Restaurant confirmation event {} arrived after order {} advanced to {}; "
                            + "receipt recorded without regressing status",
                    event.getEventId(), order.getId(), order.getStatus());
            return;
        }

        transition(order, OrderStatus.CONFIRMED);
        order.setUpdatedAt(LocalDateTime.now());

        if (event.getNotes() != null) {
            appendNotes(order, "Restaurant confirmed: " + event.getNotes());
        }

        orderRepository.save(order);
        
        log.info("✅ Order {} confirmed by restaurant", order.getId());
    }

    @Override
    @Transactional
    public void handleRestaurantRejected(RestaurantEvent event) {
        log.info("🏪 Processing restaurant rejection: orderId={}, reason={}", 
                event.getOrderId(), event.getRejectionReason());

        Order order = findOrderById(event.getOrderId());

        validateRestaurantEvent(order, event);
        if (registerDecisionReceiptOrIdentifyExactReplay(event, "REJECTED")) {
            log.info("Restaurant rejection event {} already applied for order {}, skipping exact replay",
                    event.getEventId(), order.getId());
            return;
        }
        String canonicalCancelReason = RestaurantPaymentPolicy.restaurantRejectionReason(
                order.getStatus() == null ? null : order.getStatus().toDomain(), event.getRejectionReason());

        String previousStatus = order.getStatus().name();
        transition(order, OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());

        order.setCancelReason(canonicalCancelReason);
        appendNotes(order, canonicalCancelReason);

        orderRepository.save(order);

        // ✅ Nhà hàng từ chối → dừng luôn việc tìm/giao shipper.
        //    Tái dùng luồng huỷ hiện có: publish order.cancelled → Saga điều phối
        //    saga.command.cancel-delivery → delivery ngừng matching.
        // cancelledBy is the authenticated actor user ID, never a restaurant ID.
        orderEventPublisher.publishOrderCancelledEvent(order, previousStatus, event.getActorUserId(),
                "RESTAURANT", "RESTAURANT_REJECTED");

        log.info("✅ Order {} rejected by restaurant → published cancellation to stop delivery", order.getId());
    }

    @Override
    @Transactional
    public void handleShipperAccepted(ShipperEvent event) {
        log.info("🚚 Processing shipper acceptance: orderId={}, shipperId={}", 
                event.getOrderId(), event.getShipperId());

        Order order = findOrderById(event.getOrderId());

        com.delivery.identity.contracts.SimulationContext expected =
                com.delivery.identity.contracts.SimulationContext.orReal(order.getSimulationContext());
        com.delivery.identity.contracts.SimulationContext actual =
                com.delivery.identity.contracts.SimulationContext.orReal(event.getSimulationContext());
        expected.requireValid();
        actual.requireValid();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("shipper acceptance execution context does not match order");
        }

        if (com.delivery.order.domain.SagaStatusPolicy.assignmentReplay(
                order.getStatus().toDomain(), order.getShipperId(), event.getShipperId())) {
            log.info("Shipper assignment already applied for order {}, skipping replay", order.getId());
            return;
        }

        transition(order, OrderStatus.ASSIGNED);
        order.setShipperId(event.getShipperId());
        order.setUpdatedAt(LocalDateTime.now());

        if (event.getNotes() != null) {
            appendNotes(order, "Shipper accepted: " + event.getNotes());
        }

        orderRepository.save(order);
        
        log.info("✅ Order {} assigned to shipper {}", order.getId(), event.getShipperId());
    }

    /**
     * ✅ Helper method to find order by ID
     */
    private Order findOrderById(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with ID: " + orderId));
    }

    private void validateRestaurantEvent(Order order, RestaurantEvent event) {
        RestaurantPaymentPolicy.admitRestaurantDecision(event.getEventId(), event.getActorUserId(),
                event.getRestaurantId(), order.getRestaurantId());
    }

    private boolean registerDecisionReceiptOrIdentifyExactReplay(RestaurantEvent event, String decision) {
        String fingerprint = fingerprint(event);
        RestaurantDecisionReceipt byEvent = restaurantDecisionReceiptRepository
                .findById(event.getEventId())
                .orElse(null);
        if (byEvent != null) {
            requireMatchingDecisionReceipt(byEvent, event, decision, fingerprint);
            return true;
        }

        RestaurantDecisionReceipt byOrder = restaurantDecisionReceiptRepository
                .findByOrderId(event.getOrderId())
                .orElse(null);
        if (byOrder != null) {
            RestaurantPaymentPolicy.requireNoPreviousDecision(byOrder.getEventId());
        }

        restaurantDecisionReceiptRepository.saveAndFlush(RestaurantDecisionReceipt.builder()
                .eventId(event.getEventId())
                .orderId(event.getOrderId())
                .restaurantId(event.getRestaurantId())
                .decision(decision)
                .payloadFingerprint(fingerprint)
                .createdAt(LocalDateTime.now())
                .build());
        return false;
    }

    private void requireMatchingDecisionReceipt(
            RestaurantDecisionReceipt receipt,
            RestaurantEvent event,
            String decision,
            String fingerprint) {
        new RestaurantPaymentPolicy.Receipt(receipt.getOrderId(), receipt.getRestaurantId(),
                receipt.getDecision(), receipt.getPayloadFingerprint()).requireExactReplay(
                        new RestaurantPaymentPolicy.Receipt(event.getOrderId(), event.getRestaurantId(),
                                decision, fingerprint));
    }

    private String fingerprint(RestaurantEvent event) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("restaurant decision payload cannot be serialized", e);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * ✅ Null-safe note concatenation
     */
    private void appendNotes(Order order, String note) {
        String current = order.getNotes();
        order.setNotes(current != null ? current + "\n" + note : note);
    }

    /**
     * ✅ Map delivery status to order status
     */
    private OrderStatus mapDeliveryStatusToOrderStatus(String deliveryStatus) {
        return OrderStatus.valueOf(
                com.delivery.order.domain.SagaStatusPolicy.deliveryStatus(deliveryStatus).name());
    }

    private void transition(Order order, OrderStatus target) {
        order.getStatus().requireTransitionTo(target);
        order.setStatus(target);
    }

}
