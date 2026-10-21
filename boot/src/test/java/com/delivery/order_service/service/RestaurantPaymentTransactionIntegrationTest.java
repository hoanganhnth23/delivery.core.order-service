package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.dto.event.PaymentEvent;
import com.delivery.order_service.dto.event.RestaurantEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.delivery.order_service.repository.RestaurantDecisionReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RestaurantPaymentTransactionIntegrationTest {
    @Autowired OrderEventService service;
    @Autowired OrderRepository orders;
    @Autowired RestaurantDecisionReceiptRepository receipts;
    @Autowired OutboxEventRepository outbox;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        receipts.deleteAll();
        outbox.deleteAll();
    }

    @Test
    void restaurantAndSagaConvergeInBothCrossTopicOrdersWithExactReplayWithoutEffects() {
        for (boolean sagaFirst : new boolean[]{true, false}) {
            Long id = order("COD").getId();
            RestaurantEvent restaurant = restaurant(id);
            DeliveryStatusUpdatedEvent saga = new DeliveryStatusUpdatedEvent();
            saga.setOrderId(id);
            saga.setStatus("FINDING_SHIPPER");
            saga.setNotes("matching");
            if (sagaFirst) service.handleDeliveryStatusUpdate(saga);
            service.handleRestaurantConfirmed(restaurant);
            if (!sagaFirst) service.handleDeliveryStatusUpdate(saga);
            Order advanced = orders.findById(id).orElseThrow();
            service.handleRestaurantConfirmed(restaurant);
            Order replayed = orders.findById(id).orElseThrow();
            assertThat(replayed.getStatus()).isEqualTo(OrderStatus.FINDING_SHIPPER);
            assertThat(replayed.getNotes()).isEqualTo(advanced.getNotes());
            assertThat(replayed.getUpdatedAt()).isEqualTo(advanced.getUpdatedAt());
            assertThat(receipts.findById(restaurant.getEventId())).isPresent();
            assertThat(outbox.count()).isZero();
        }
        assertThat(receipts.count()).isEqualTo(2);
    }

    @Test
    void contradictoryPayloadAndOppositeDecisionFailWithoutChangingCommittedReceipt() {
        Long id = order("COD").getId();
        RestaurantEvent event = restaurant(id);
        service.handleRestaurantConfirmed(event);
        event.setNotes("changed");
        assertThatThrownBy(() -> service.handleRestaurantConfirmed(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("restaurant decision eventId replay has a contradictory payload");
        event.setNotes(null);
        assertThatThrownBy(() -> service.handleRestaurantRejected(event))
                .isInstanceOf(IllegalArgumentException.class);
        RestaurantEvent other = restaurant(id);
        assertThatThrownBy(() -> service.handleRestaurantRejected(other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order already has a restaurant decision from event " + event.getEventId());
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void rejectionCommitsOneImmutableCompensationSnapshotAndReplayIsANoop() throws Exception {
        Order order = order("COD");
        RestaurantEvent event = restaurant(order.getId());
        event.setRejectionReason("closed");
        service.handleRestaurantRejected(event);
        Order cancelled = orders.findById(order.getId()).orElseThrow();
        service.handleRestaurantRejected(event);
        assertThat(orders.findById(order.getId()).orElseThrow().getUpdatedAt()).isEqualTo(cancelled.getUpdatedAt());
        assertThat(receipts.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        var payload = json.readTree(outbox.findAll().get(0).getPayload());
        assertThat(payload.get("cancelledBy").asLong()).isEqualTo(70L);
        assertThat(payload.get("cancelledBySource").asText()).isEqualTo("RESTAURANT");
        assertThat(payload.get("cancelReasonCode").asText()).isEqualTo("RESTAURANT_REJECTED");
        assertThat(payload.get("totalPrice").decimalValue()).isEqualByComparingTo(order.getTotalPrice());
        assertThat(payload.get("flashSaleReservationId").asText()).isEqualTo(order.getFlashSaleReservationId().toString());
        assertThat(cancelled.getCancelReason()).isEqualTo("Rejected by restaurant: closed");
    }

    @Test
    void ineligibleDecisionRollsBackNewReceiptAndCanRetryIdenticalEventAfterRecovery() {
        Order order = order("COD");
        order.setStatus(OrderStatus.ASSIGNED);
        orders.saveAndFlush(order);
        RestaurantEvent event = restaurant(order.getId());
        assertThatThrownBy(() -> service.handleRestaurantRejected(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Không thể từ chối đơn ở trạng thái ASSIGNED");
        assertThat(receipts.findById(event.getEventId())).isEmpty();
        assertThat(outbox.count()).isZero();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        order.setStatus(OrderStatus.PENDING);
        orders.saveAndFlush(order);
        service.handleRestaurantRejected(event);
        assertThat(receipts.findById(event.getEventId())).isPresent();
    }

    @Test
    void receiptCancellationAndOutboxRollBackTogetherAndRemainReplayable() {
        Long id = order("COD").getId();
        RestaurantEvent event = restaurant(id);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            service.handleRestaurantRejected(event);
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);
        assertThat(receipts.findById(event.getEventId())).isEmpty();
        assertThat(outbox.count()).isZero();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        service.handleRestaurantRejected(event);
        assertThat(receipts.findById(event.getEventId())).isPresent();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void paymentCompatibilityPreservesCodNoopsAndOnlineCancellationSnapshot() throws Exception {
        Order cod = order("COD");
        PaymentEvent payment = new PaymentEvent();
        payment.setOrderId(cod.getId());
        payment.setAmount(115000.0);
        service.handlePaymentCompleted(payment);
        service.handlePaymentFailed(payment);
        assertThat(orders.findById(cod.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(outbox.count()).isZero();
        Order online = order("ONLINE");
        payment.setOrderId(online.getId());
        service.handlePaymentCompleted(payment);
        Order confirmed = orders.findById(online.getId()).orElseThrow();
        service.handlePaymentCompleted(payment);
        assertThat(orders.findById(online.getId()).orElseThrow().getUpdatedAt()).isEqualTo(confirmed.getUpdatedAt());
        service.handlePaymentFailed(payment);
        Order cancelled = orders.findById(online.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo("Payment failed");
        assertThat(cancelled.getCancelledBy()).isEqualTo(7L);
        var payload = json.readTree(outbox.findAll().get(0).getPayload());
        assertThat(payload.get("cancelledBySource").asText()).isEqualTo("SYSTEM");
        assertThat(payload.get("cancelReasonCode").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(payload.get("previousStatus").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void onlineFailureAfterPickupRollsBackWithoutCancellationOrOutbox() {
        Order order = order("ONLINE");
        order.setStatus(OrderStatus.PICKED_UP);
        orders.saveAndFlush(order);
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(order.getId());
        event.setFailureReason("declined");
        assertThatThrownBy(() -> service.handlePaymentFailed(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid order transition: PICKED_UP -> CANCELLED");
        Order unchanged = orders.findById(order.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(unchanged.getCancelReason()).isNull();
        assertThat(outbox.count()).isZero();
    }

    private Order order(String paymentMethod) {
        Order order = new Order();
        order.setUserId(7L);
        order.setCreatorId(7L);
        order.setRestaurantId(8L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod(paymentMethod);
        order.setSubtotalPrice(new BigDecimal("100000"));
        order.setShippingFee(new BigDecimal("15000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalPrice(new BigDecimal("115000"));
        order.setFlashSaleReservationId(UUID.randomUUID());
        return orders.saveAndFlush(order);
    }

    private RestaurantEvent restaurant(Long id) {
        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrderId(id);
        event.setRestaurantId(8L);
        event.setActorUserId(70L);
        return event;
    }

    private static final class DeliberateRollback extends RuntimeException { }
}
