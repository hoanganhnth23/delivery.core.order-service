package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.dto.event.ShipperEvent;
import com.delivery.order_service.dto.event.ShipperNotFoundEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies one validated Saga command in the same transaction as its inbox
 * receipt. The Kafka listener acknowledges only after this method returns.
 */
@Service
public class SagaOrderCommandProcessor {

    private final SagaCommandReceiptService receipts;
    private final OrderEventService orderEventService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public SagaOrderCommandProcessor(SagaCommandReceiptService receipts,
                                     OrderEventService orderEventService,
                                     OrderService orderService,
                                     OrderRepository orderRepository) {
        this.receipts = receipts;
        this.orderEventService = orderEventService;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public boolean applyDeliveryStatus(UUID eventId, Long orderId, String sagaStatus,
                                       String rawPayload, long sequence, DeliveryStatusUpdatedEvent event) {
        if (!claim(eventId, orderId, sagaStatus, rawPayload)) {
            return false;
        }
        if (!claimNextSequence(orderId, sequence)) return true;
        orderEventService.handleDeliveryStatusUpdate(event);
        return true;
    }

    /** Source-compatible test/legacy entry point while the rolling migration drains. */
    @Transactional
    public boolean applyDeliveryStatus(UUID eventId, Long orderId, String sagaStatus,
                                       String rawPayload, DeliveryStatusUpdatedEvent event) {
        return applyDeliveryStatus(eventId, orderId, sagaStatus, rawPayload, 0, event);
    }

    @Transactional
    public boolean applyShipperAccepted(UUID eventId, Long orderId, String sagaStatus,
                                        String rawPayload, long sequence, ShipperEvent event) {
        if (!claim(eventId, orderId, sagaStatus, rawPayload)) {
            return false;
        }
        if (!claimNextSequence(orderId, sequence)) return true;
        orderEventService.handleShipperAccepted(event);
        return true;
    }

    @Transactional
    public boolean applyShipperAccepted(UUID eventId, Long orderId, String sagaStatus,
                                        String rawPayload, ShipperEvent event) {
        return applyShipperAccepted(eventId, orderId, sagaStatus, rawPayload, 0, event);
    }

    @Transactional
    public boolean applyShipperNotFound(UUID eventId, Long orderId, String sagaStatus,
                                        String rawPayload, long sequence, ShipperNotFoundEvent event) {
        if (!claim(eventId, orderId, sagaStatus, rawPayload)) {
            return false;
        }
        if (!claimNextSequence(orderId, sequence)) return true;
        orderService.updateOrderStatusFromShipperNotFoundEvent(event);
        return true;
    }

    @Transactional
    public boolean applyShipperNotFound(UUID eventId, Long orderId, String sagaStatus,
                                        String rawPayload, ShipperNotFoundEvent event) {
        return applyShipperNotFound(eventId, orderId, sagaStatus, rawPayload, 0, event);
    }

    private boolean claim(UUID eventId, Long orderId, String sagaStatus, String rawPayload) {
        return receipts.claim(eventId, SagaCommandReceiptService.UPDATE_ORDER_STATUS,
                orderId, sagaStatus, rawPayload);
    }

    /**
     * A higher sequence is never applied speculatively. Throwing rolls back the
     * receipt so the same Kafka record is retried on its original partition.
     */
    private boolean claimNextSequence(Long orderId, long sequence) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        long cursor = order.getLastSagaStatusSequence();
        return switch (com.delivery.order.domain.SagaStatusPolicy.sequence(cursor, sequence)) {
            case LEGACY -> true;
            case STALE -> false;
            case GAP -> throw new SagaOrderSequenceGapException(orderId, cursor + 1, sequence);
            case NEXT -> {
                order.setLastSagaStatusSequence(sequence);
                yield true;
            }
        };
    }
}
