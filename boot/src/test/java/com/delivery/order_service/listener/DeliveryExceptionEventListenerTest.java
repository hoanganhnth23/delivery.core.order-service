package com.delivery.order_service.listener;

import com.delivery.order_service.service.OrderEventService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DeliveryExceptionEventListenerTest {

    private final OrderEventService service = mock(OrderEventService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final DeliveryExceptionEventListener listener = new DeliveryExceptionEventListener(service);

    /** Shape published by delivery-service (DeliveryExceptionReportedEvent). */
    private static String event(String exceptionStatus) {
        return """
                {"eventId":"0b8e7b6c-56a4-3b8e-9d0f-6a1f2e3d4c5b","eventType":"DELIVERY_EXCEPTION_UPDATED",
                 "occurredAt":"2026-10-25T07:08:10","exceptionId":"5f0c1d2e-3b4a-4c5d-8e9f-0a1b2c3d4e5f",
                 "deliveryId":12,"orderId":12,"userId":32,"userPrincipalId":32,"restaurantId":7,"shipperId":5,
                 "previousDeliveryStatus":"RETURNING","currentDeliveryStatus":"RETURNED",
                 "exceptionStatus":"%s","reason":"Khách không nghe máy","paymentMethod":"COD",
                 "subtotalPrice":45000.0,"discountAmount":0,"shippingFee":12000.0,"totalPrice":57000.0}
                """.formatted(exceptionStatus);
    }

    @Test
    void restaurantConfirmedReturnCancelsTheOrder() {
        listener.handleDeliveryException(event("RETURNED"), ack);

        verify(service).handleDeliveryReturned(12L, "Khách không nghe máy");
        verify(ack).acknowledge();
    }

    @Test
    void earlierExceptionStepsAreAcknowledgedWithoutTouchingTheOrder() {
        for (String status : new String[] {"RETURN_AVAILABLE", "RETRY_AVAILABLE", "RETRY_USED", "RETURNING", "RESOLVED"}) {
            listener.handleDeliveryException(event(status), ack);
        }
        verifyNoInteractions(service);
        verify(ack, times(5)).acknowledge();
    }

    @Test
    void malformedEventsAreRejectedWithoutAcknowledging() {
        assertThatThrownBy(() -> listener.handleDeliveryException("{not-json", ack))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.handleDeliveryException("{\"exceptionStatus\":\"RETURNED\"}", ack))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service, ack);
    }
}
