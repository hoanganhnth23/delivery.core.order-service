package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.dto.event.PaymentEvent;
import com.delivery.order_service.dto.event.RestaurantEvent;
import com.delivery.order_service.dto.event.ShipperEvent;

/**
 * ✅ Order Event Service Interface theo AI Coding Instructions
 */
public interface OrderEventService {

    /**
     * Handle delivery status update events
     */
    void handleDeliveryStatusUpdate(DeliveryStatusUpdatedEvent event);

    /**
     * Handle payment completed events
     */
    void handlePaymentCompleted(PaymentEvent event);

    /**
     * Handle payment failed events
     */
    void handlePaymentFailed(PaymentEvent event);

    /**
     * Handle restaurant confirmation events
     */
    void handleRestaurantConfirmed(RestaurantEvent event);

    /**
     * Handle restaurant rejection events
     */
    void handleRestaurantRejected(RestaurantEvent event);

    /**
     * Handle shipper accepted events
     */
    void handleShipperAccepted(ShipperEvent event);

    /**
     * Handle shipper rejected events
     */
}
