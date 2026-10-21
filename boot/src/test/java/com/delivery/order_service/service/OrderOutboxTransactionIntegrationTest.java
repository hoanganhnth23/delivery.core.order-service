package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.RestaurantEvent;
import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderItemRepository;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.RestaurantDecisionReceiptRepository;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OrderOutboxTransactionIntegrationTest {

    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired com.delivery.order_service.repository.OutboxEventRepository outboxRepository;
    @Autowired RestaurantDecisionReceiptRepository restaurantDecisionReceiptRepository;
    @Autowired SagaCommandReceiptRepository sagaCommandReceiptRepository;
    @Autowired OrderEventPublisher eventPublisher;
    @Autowired OrderEventService orderEventService;
    @Autowired OrderService orderService;
    @Autowired SagaOrderCommandProcessor sagaOrderCommandProcessor;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAll();
        restaurantDecisionReceiptRepository.deleteAll();
        sagaCommandReceiptRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void readOrderMapsLazyItemsInsideReadOnlyServiceTransaction() {
        Order order = transactionTemplate.execute(status -> {
            Order stored = orderRepository.save(newOrder());
            OrderItem item = new OrderItem();
            item.setOrder(stored);
            item.setMenuItemId(99L);
            item.setMenuItemName("Canonical item");
            item.setQuantity(2);
            item.setPrice(new BigDecimal("50000"));
            orderItemRepository.save(item);
            return stored;
        });

        var response = orderService.getOrderById(order.getId(), order.getUserId(), "USER");

        assertThat(response.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuItemId()).isEqualTo(99L);
            assertThat(item.getMenuItemName()).isEqualTo("Canonical item");
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    @Test
    void orderAndEventCommitAtomically() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.save(newOrder());
            eventPublisher.publishOrderCreatedEvent(order);
        });

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        var outbox = outboxRepository.findAll().get(0);
        var payload = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(payload.path("eventId").asText()).isEqualTo(outbox.getEventId().toString());
        assertThat(payload.path("orderId").asLong()).isPositive();
    }

    @Test
    void rollbackRemovesBothOrderAndOutboxEvent() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.save(newOrder());
            eventPublisher.publishOrderCreatedEvent(order);
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        assertThat(orderRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void restaurantRejectionCommitsReceiptOrderAndCancellationOutboxAtomically() throws Exception {
        Order order = orderRepository.save(newOrder());
        RestaurantEvent event = restaurantRejection(order, UUID.randomUUID());

        orderEventService.handleRestaurantRejected(event);
        orderEventService.handleRestaurantRejected(event);

        Order stored = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored.getCancelReason()).isEqualTo("Rejected by restaurant: Kitchen closed");
        var receipt = restaurantDecisionReceiptRepository.findById(event.getEventId()).orElseThrow();
        assertThat(receipt.getOrderId()).isEqualTo(order.getId());
        assertThat(receipt.getDecision()).isEqualTo("REJECTED");
        assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getTopic()).isEqualTo("order.cancelled");
            try {
                var payload = objectMapper.readTree(outbox.getPayload());
                assertThat(payload.path("orderId").asLong()).isEqualTo(order.getId());
                assertThat(payload.path("cancelledBy").asLong()).isEqualTo(event.getActorUserId());
                assertThat(payload.path("cancelledBy").asLong()).isNotEqualTo(order.getRestaurantId());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void lateRestaurantConfirmationRecordsReceiptWithoutRegressingSagaAdvancedOrder() {
        Order candidate = newOrder();
        candidate.setStatus(OrderStatus.FINDING_SHIPPER);
        Order order = orderRepository.save(candidate);
        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrderId(order.getId());
        event.setRestaurantId(order.getRestaurantId());
        event.setActorUserId(70L);
        event.setStatus("CONFIRMED");
        event.setAction("CONFIRM");

        orderEventService.handleRestaurantConfirmed(event);

        assertThat(restaurantDecisionReceiptRepository.findById(event.getEventId()))
                .hasValueSatisfying(receipt -> {
                    assertThat(receipt.getOrderId()).isEqualTo(order.getId());
                    assertThat(receipt.getDecision()).isEqualTo("CONFIRMED");
                });
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FINDING_SHIPPER);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void sagaCommandReceiptAndOrderMutationCommitOrRollbackTogether() {
        Order order = orderRepository.save(newOrder());
        UUID successfulEventId = UUID.randomUUID();
        String successPayload = "{\"eventId\":\"" + successfulEventId + "\"}";
        DeliveryStatusUpdatedEvent findingShipper = new DeliveryStatusUpdatedEvent();
        findingShipper.setOrderId(order.getId());
        findingShipper.setStatus("FINDING_SHIPPER");

        assertThat(sagaOrderCommandProcessor.applyDeliveryStatus(successfulEventId, order.getId(),
                "FINDING_SHIPPER", successPayload, findingShipper)).isTrue();
        assertThat(sagaCommandReceiptRepository.findById(successfulEventId)).isPresent();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FINDING_SHIPPER);
        assertThat(sagaOrderCommandProcessor.applyDeliveryStatus(successfulEventId, order.getId(),
                "FINDING_SHIPPER", successPayload, findingShipper)).isFalse();

        UUID failingEventId = UUID.randomUUID();
        DeliveryStatusUpdatedEvent invalidDelivery = new DeliveryStatusUpdatedEvent();
        invalidDelivery.setOrderId(order.getId());
        invalidDelivery.setStatus("DELIVERED");
        assertThatThrownBy(() -> sagaOrderCommandProcessor.applyDeliveryStatus(failingEventId, order.getId(),
                "DELIVERED", "{\"eventId\":\"" + failingEventId + "\"}", invalidDelivery))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sagaCommandReceiptRepository.findById(failingEventId)).isEmpty();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FINDING_SHIPPER);
    }

    @Test
    void customerCancellationCommitsImmutableSnapshotAndExactReplayHasNoEffects() throws Exception {
        Order candidate = newOrder();
        candidate.setUserPrincipalId(901L);
        candidate.setInventoryReservationId(UUID.randomUUID());
        Order order = orderRepository.save(candidate);
        orderService.cancelOrder(order.getId(), 901L, 10L, "USER", "changed mind");
        Order cancelled = orderRepository.findById(order.getId()).orElseThrow();
        var outbox = outboxRepository.findAll().get(0);
        var payload = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getTopic()).isEqualTo("order.cancelled");
        assertThat(payload.path("cancelledBySource").asText()).isEqualTo("CUSTOMER");
        assertThat(payload.path("cancelReasonCode").asText()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(payload.path("previousStatus").asText()).isEqualTo("PENDING");
        assertThat(payload.path("inventoryReservationId").asText()).isEqualTo(order.getInventoryReservationId().toString());
        assertThat(payload.path("totalPrice").decimalValue()).isEqualByComparingTo(order.getTotalPrice());
        orderService.cancelOrder(order.getId(), 901L, 99L, "USER", "changed mind");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getUpdatedAt()).isEqualTo(cancelled.getUpdatedAt());
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll().get(0).getPayload()).isEqualTo(outbox.getPayload());
        assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), 901L, 10L, "USER", "different"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Order cancellation already exists with a different actor or reason");
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void customerCancellationRollsBackWithOutboxAndCanRetry() {
        Order order = orderRepository.save(newOrder());
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            orderService.cancelOrder(order.getId(), 10L, "USER", "changed mind");
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(outboxRepository.count()).isZero();
        orderService.cancelOrder(order.getId(), 10L, "USER", "changed mind");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    private Order newOrder() {
        Order order = new Order();
        order.setUserId(10L);
        order.setCreatorId(20L);
        order.setRestaurantId(30L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod("COD");
        order.setSubtotalPrice(new BigDecimal("100000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingFee(new BigDecimal("15000"));
        order.setTotalPrice(new BigDecimal("115000"));
        return order;
    }

    private RestaurantEvent restaurantRejection(Order order, UUID eventId) {
        RestaurantEvent event = new RestaurantEvent();
        event.setEventId(eventId);
        event.setOrderId(order.getId());
        event.setRestaurantId(order.getRestaurantId());
        event.setActorUserId(70L);
        event.setStatus("REJECTED");
        event.setAction("REJECT");
        event.setRejectionReason("Kitchen closed");
        return event;
    }

    private static final class DeliberateRollback extends RuntimeException {
    }
}
