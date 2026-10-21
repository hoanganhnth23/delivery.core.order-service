package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.dto.event.PaymentEvent;
import com.delivery.order_service.dto.event.RestaurantEvent;
import com.delivery.order_service.dto.event.ShipperEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.entity.RestaurantDecisionReceipt;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.RestaurantDecisionReceiptRepository;
import com.delivery.order_service.service.impl.OrderEventServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ✅ Order Event Service Test theo AI Coding Instructions
 */
@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private RestaurantDecisionReceiptRepository restaurantDecisionReceiptRepository;

    private OrderEventService orderEventService;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        // ✅ Constructor Injection (MANDATORY)
        lenient().when(restaurantDecisionReceiptRepository.findById(any()))
                .thenReturn(Optional.empty());
        lenient().when(restaurantDecisionReceiptRepository.findByOrderId(any()))
                .thenReturn(Optional.empty());
        orderEventService = new OrderEventServiceImpl(
                orderRepository,
                orderEventPublisher,
                restaurantDecisionReceiptRepository,
                new ObjectMapper().findAndRegisterModules());

        // Setup test order
        testOrder = new Order();
        testOrder.setId(1L);
        testOrder.setUserId(123L);
        testOrder.setRestaurantId(456L);
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setPaymentMethod("COD");
        testOrder.setTotalPrice(new BigDecimal("150000"));
        testOrder.setNotes("");
        testOrder.setCreatedAt(LocalDateTime.now());
        testOrder.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void testHandleDeliveryStatusUpdate_Success() {
        // Given
        testOrder.setStatus(OrderStatus.DELIVERING);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(1L);
        event.setStatus("DELIVERED");
        event.setNotes("Delivered successfully");
        event.setUpdatedAt(LocalDateTime.now());

        // When
        orderEventService.handleDeliveryStatusUpdate(event);

        // Then
        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository).save(any(Order.class));
        assertEquals(OrderStatus.DELIVERED, testOrder.getStatus());
    }

    @Test
    void testHandlePaymentCompleted_Success() {
        // Given
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setPaymentMethod("ONLINE");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(1L);
        event.setStatus("COMPLETED");
        event.setAmount(150000.0);
        event.setProcessedAt(LocalDateTime.now());

        // When
        orderEventService.handlePaymentCompleted(event);

        // Then
        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository).save(any(Order.class));
        assertEquals(OrderStatus.CONFIRMED, testOrder.getStatus());
    }

    @Test
    void testHandleRestaurantConfirmed_Success() {
        // Given
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);
        event.setStatus("CONFIRMED");
        event.setEstimatedPrepTime(25);
        event.setNotes("Order confirmed, will be ready in 25 minutes");
        event.setProcessedAt(LocalDateTime.now());

        // When
        orderEventService.handleRestaurantConfirmed(event);

        // Then
        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository).save(any(Order.class));
        verify(restaurantDecisionReceiptRepository).saveAndFlush(any(RestaurantDecisionReceipt.class));
        assertEquals(OrderStatus.CONFIRMED, testOrder.getStatus());
    }

    @Test
    void restaurantConfirmationRejectsMismatchedRestaurant() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(999L);
        event.setActorUserId(70L);

        assertThrows(IllegalArgumentException.class,
                () -> orderEventService.handleRestaurantConfirmed(event));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void exactRestaurantConfirmationReplayIsIdempotentAfterOrderProgresses() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);

        orderEventService.handleRestaurantConfirmed(event);
        RestaurantDecisionReceipt receipt = mockingDetails(restaurantDecisionReceiptRepository)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAndFlush"))
                .map(invocation -> (RestaurantDecisionReceipt) invocation.getArgument(0))
                .findFirst()
                .orElseThrow();

        testOrder.setStatus(OrderStatus.FINDING_SHIPPER);
        when(restaurantDecisionReceiptRepository.findById(event.getEventId()))
                .thenReturn(Optional.of(receipt));
        clearInvocations(orderRepository, restaurantDecisionReceiptRepository);

        orderEventService.handleRestaurantConfirmed(event);

        verify(orderRepository, never()).save(any(Order.class));
        verify(restaurantDecisionReceiptRepository, never())
                .saveAndFlush(any(RestaurantDecisionReceipt.class));
    }

    @Test
    void delayedRestaurantConfirmationRecordsReceiptAfterSagaAdvancedOrder() {
        testOrder.setStatus(OrderStatus.FINDING_SHIPPER);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);

        orderEventService.handleRestaurantConfirmed(event);

        verify(orderRepository, never()).save(any(Order.class));
        verify(restaurantDecisionReceiptRepository)
                .saveAndFlush(argThat(receipt -> receipt.getEventId().equals(event.getEventId())
                        && receipt.getDecision().equals("CONFIRMED")));
    }

    @Test
    void differentRestaurantDecisionEventCannotReuseAnOrderReceipt() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        RestaurantDecisionReceipt receipt = RestaurantDecisionReceipt.builder()
                .eventId(UUID.randomUUID())
                .orderId(1L)
                .restaurantId(456L)
                .decision("CONFIRMED")
                .payloadFingerprint("a".repeat(64))
                .createdAt(LocalDateTime.now())
                .build();
        when(restaurantDecisionReceiptRepository.findByOrderId(1L))
                .thenReturn(Optional.of(receipt));

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);

        assertThrows(IllegalStateException.class,
                () -> orderEventService.handleRestaurantConfirmed(event));
        verify(orderRepository, never()).save(any(Order.class));
        verify(restaurantDecisionReceiptRepository, never())
                .saveAndFlush(any(RestaurantDecisionReceipt.class));
    }

    @Test
    void sameRestaurantDecisionEventIdCannotChangePayload() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);
        event.setStatus("CONFIRMED");
        event.setNotes("original");

        orderEventService.handleRestaurantConfirmed(event);
        RestaurantDecisionReceipt receipt = mockingDetails(restaurantDecisionReceiptRepository)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAndFlush"))
                .map(invocation -> (RestaurantDecisionReceipt) invocation.getArgument(0))
                .findFirst()
                .orElseThrow();

        testOrder.setStatus(OrderStatus.FINDING_SHIPPER);
        event.setNotes("altered");
        when(restaurantDecisionReceiptRepository.findById(event.getEventId()))
                .thenReturn(Optional.of(receipt));
        clearInvocations(orderRepository, restaurantDecisionReceiptRepository);

        assertThrows(IllegalArgumentException.class,
                () -> orderEventService.handleRestaurantConfirmed(event));
        verify(orderRepository, never()).save(any(Order.class));
        verify(restaurantDecisionReceiptRepository, never())
                .saveAndFlush(any(RestaurantDecisionReceipt.class));
    }

    @Test
    void restaurantRejectionCannotMasqueradeAsDuplicateOfAnotherCancellation() {
        testOrder.setStatus(OrderStatus.CANCELLED);
        testOrder.setCancelReason("Cancelled by customer");
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);
        event.setRejectionReason("Kitchen closed");

        assertThrows(IllegalStateException.class,
                () -> orderEventService.handleRestaurantRejected(event));
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void testHandleShipperAccepted_Success() {
        // Given
        testOrder.setStatus(OrderStatus.WAIT_SHIPPER_CONFIRM);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        ShipperEvent event = new ShipperEvent();
        event.setOrderId(1L);
        event.setShipperId(789L);
        event.setAction("ACCEPTED");
        event.setEstimatedPickupTime(15.0);
        event.setNotes("Shipper on the way");
        event.setResponseTime(LocalDateTime.now());

        // When
        orderEventService.handleShipperAccepted(event);

        // Then
        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository).save(any(Order.class));
        assertEquals(OrderStatus.ASSIGNED, testOrder.getStatus());
        assert testOrder.getShipperId().equals(789L);
    }

    @Test
    void duplicateShipperAcceptanceIsIdempotent() {
        testOrder.setStatus(OrderStatus.ASSIGNED);
        testOrder.setShipperId(789L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        ShipperEvent event = new ShipperEvent();
        event.setOrderId(1L);
        event.setShipperId(789L);

        orderEventService.handleShipperAccepted(event);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void differentShipperCannotOverwriteAssignedOrder() {
        testOrder.setStatus(OrderStatus.ASSIGNED);
        testOrder.setShipperId(789L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        ShipperEvent event = new ShipperEvent();
        event.setOrderId(1L);
        event.setShipperId(999L);

        assertThrows(IllegalStateException.class,
                () -> orderEventService.handleShipperAccepted(event));
        assertEquals(789L, testOrder.getShipperId());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void rematchStatusClearsPreviousShipper() {
        testOrder.setStatus(OrderStatus.ASSIGNED);
        testOrder.setShipperId(789L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(1L);
        event.setStatus("FINDING_SHIPPER");

        orderEventService.handleDeliveryStatusUpdate(event);

        assertEquals(OrderStatus.FINDING_SHIPPER, testOrder.getStatus());
        assertEquals(null, testOrder.getShipperId());
        verify(orderRepository).save(testOrder);
    }

    @Test
    void sagaMatchingCommandConvergesWhenRestaurantConsumerHasNotCommittedYet() {
        testOrder.setStatus(OrderStatus.PENDING);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(1L);
        event.setStatus("FINDING_SHIPPER");

        orderEventService.handleDeliveryStatusUpdate(event);

        assertEquals(OrderStatus.FINDING_SHIPPER, testOrder.getStatus());
        verify(orderRepository).save(testOrder);
    }

    @Test
    void unknownDeliveryStatusFailsClosed() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(1L);
        event.setStatus("TELEPORTED");

        assertThrows(IllegalArgumentException.class,
                () -> orderEventService.handleDeliveryStatusUpdate(event));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void simulationDeliveryStatusCannotMutateOrderFromAnotherRun() {
        UUID expectedRun = UUID.randomUUID();
        UUID cohort = UUID.randomUUID();
        testOrder.setSimulationContext(new SimulationContext(
                SimulationContext.ExecutionMode.SIMULATION, expectedRun, cohort, 1L));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(1L);
        event.setStatus("DELIVERED");
        event.setSimulationContext(new SimulationContext(
                SimulationContext.ExecutionMode.SIMULATION, UUID.randomUUID(), cohort, 1L));

        assertThrows(IllegalArgumentException.class,
                () -> orderEventService.handleDeliveryStatusUpdate(event));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void paymentFailureCannotCancelCodOrder() {
        // Given
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(1L);
        event.setStatus("FAILED");
        event.setFailureReason("Insufficient funds");
        event.setProcessedAt(LocalDateTime.now());

        // When
        orderEventService.handlePaymentFailed(event);

        // Then
        verify(orderRepository).findByIdForUpdate(1L);
        verify(orderRepository, never()).save(any(Order.class));
        assertEquals(OrderStatus.PENDING, testOrder.getStatus());
    }

    @Test
    void onlinePaymentFailurePublishesCompensationWithReservationIdentities() {
        testOrder.setPaymentMethod("ONLINE");
        testOrder.setVoucherReservationId(UUID.randomUUID());
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(testOrder)).thenReturn(testOrder);
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(1L);
        event.setStatus("FAILED");
        event.setFailureReason("Gateway timeout");

        orderEventService.handlePaymentFailed(event);

        assertEquals(OrderStatus.CANCELLED, testOrder.getStatus());
        verify(orderEventPublisher).publishOrderCancelledEvent(testOrder, "PENDING", 123L,
                "SYSTEM", "PAYMENT_FAILED");
        assertEquals("Gateway timeout", testOrder.getCancelReason());
    }

    @Test
    void restaurantRejectionPublishesCompensationWithoutDroppingReservationIdentities() {
        UUID flashId = UUID.randomUUID();
        testOrder.setFlashSaleReservationId(flashId);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(testOrder)).thenReturn(testOrder);
        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(decisionEventId());
        event.setOrderId(1L);
        event.setRestaurantId(456L);
        event.setActorUserId(70L);
        event.setRejectionReason("Kitchen closed");

        orderEventService.handleRestaurantRejected(event);

        assertEquals(OrderStatus.CANCELLED, testOrder.getStatus());
        assertEquals(null, testOrder.getVoucherReservationId());
        assertEquals(flashId, testOrder.getFlashSaleReservationId());
        verify(orderEventPublisher).publishOrderCancelledEvent(testOrder, "PENDING", 70L,
                "RESTAURANT", "RESTAURANT_REJECTED");
    }

    private UUID decisionEventId() {
        return UUID.fromString("11111111-1111-1111-1111-111111111111");
    }
}
