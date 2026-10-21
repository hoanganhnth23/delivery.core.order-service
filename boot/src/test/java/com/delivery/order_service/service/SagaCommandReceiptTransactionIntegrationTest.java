package com.delivery.order_service.service;

import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SagaCommandReceiptTransactionIntegrationTest {

    @Autowired SagaCommandReceiptRepository repository;
    @Autowired SagaCommandReceiptService receipts;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired SagaOrderCommandProcessor processor;
    @Autowired com.delivery.order_service.repository.OrderRepository orders;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void claimCommitsWithTheEnclosingOrderCommandTransaction() {
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status ->
                receipts.claim(eventId, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                        "FINDING_SHIPPER", "{\"eventId\":\"" + eventId + "\"}"));

        assertThat(repository.findById(eventId)).isPresent();
    }

    @Test
    void rollbackAfterClaimLeavesTheKafkaCommandReplayable() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\"}";

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            receipts.claim(eventId, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                    "FINDING_SHIPPER", payload);
            throw new DeliberateRollback();
        })).isInstanceOf(DeliberateRollback.class);

        assertThat(repository.findById(eventId)).isEmpty();
        assertThat(receipts.claim(eventId, SagaCommandReceiptService.UPDATE_ORDER_STATUS,
                101L, "FINDING_SHIPPER", payload)).isTrue();
    }

    @Test
    void sequencedStatusCommitsCursorReceiptAndBridgeThenStaleReplayHasNoEffects() {
        var order = pendingOrder();
        order.setShipperId(88L);
        order = orders.saveAndFlush(order);
        Long id = order.getId();
        UUID first = UUID.randomUUID();
        var event = deliveryEvent(id, "FINDING_SHIPPER", "first");
        assertThat(processor.applyDeliveryStatus(first, id, "FINDING_SHIPPER", "first-payload", 1, event)).isTrue();
        var applied = orders.findById(id).orElseThrow();
        assertThat(applied.getStatus()).isEqualTo(com.delivery.order_service.entity.OrderStatus.FINDING_SHIPPER);
        assertThat(applied.getShipperId()).isNull();
        assertThat(applied.getLastSagaStatusSequence()).isEqualTo(1);
        assertThat(applied.getNotes()).isEqualTo("first");
        assertThat(repository.findById(first)).isPresent();
        assertThat(processor.applyDeliveryStatus(first, id, "FINDING_SHIPPER", "first-payload", 1, event)).isFalse();

        UUID stale = UUID.randomUUID();
        assertThat(processor.applyDeliveryStatus(stale, id, "CANCELLED", "stale-payload", 1,
                deliveryEvent(id, "CANCELLED", "must not append"))).isTrue();
        assertThat(repository.findById(stale)).isPresent();
        var unchanged = orders.findById(id).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(applied.getStatus());
        assertThat(unchanged.getLastSagaStatusSequence()).isEqualTo(1);
        assertThat(unchanged.getNotes()).isEqualTo(applied.getNotes());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(applied.getUpdatedAt());
    }

    @Test
    void gapAndInvalidTransitionRollBackReceiptAndCursorAndRemainReplayable() {
        Long id = orders.saveAndFlush(pendingOrder()).getId();
        UUID gap = UUID.randomUUID();
        var event = deliveryEvent(id, "FINDING_SHIPPER", "next");
        var gapEvent = deliveryEvent(id, "WAIT_SHIPPER_CONFIRM", null);
        assertThatThrownBy(() -> processor.applyDeliveryStatus(gap, id, "WAIT_SHIPPER_CONFIRM", "gap", 2, gapEvent))
                .isInstanceOf(SagaOrderSequenceGapException.class)
                .hasMessage("Saga status sequence gap for orderId=" + id + ": expected=1, actual=2");
        assertThat(repository.findById(gap)).isEmpty();
        assertThat(orders.findById(id).orElseThrow().getLastSagaStatusSequence()).isZero();
        UUID invalid = UUID.randomUUID();
        assertThatThrownBy(() -> processor.applyDeliveryStatus(invalid, id, "DELIVERED", "invalid", 1,
                deliveryEvent(id, "DELIVERED", null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid order transition: PENDING -> DELIVERED");
        assertThat(repository.findById(invalid)).isEmpty();
        assertThat(orders.findById(id).orElseThrow().getLastSagaStatusSequence()).isZero();
        processor.applyDeliveryStatus(UUID.randomUUID(), id, "FINDING_SHIPPER", "first", 1, event);
        assertThat(processor.applyDeliveryStatus(gap, id, "WAIT_SHIPPER_CONFIRM", "gap", 2, gapEvent)).isTrue();
        assertThat(orders.findById(id).orElseThrow().getLastSagaStatusSequence()).isEqualTo(2);
    }

    @Test
    void legacyCommandsRemainAcceptedUntilFirstSequenceThenFailWithoutReceipt() {
        Long id = orders.saveAndFlush(pendingOrder()).getId();
        var event = deliveryEvent(id, "FINDING_SHIPPER", null);
        processor.applyDeliveryStatus(UUID.randomUUID(), id, "FINDING_SHIPPER", "legacy", event);
        assertThat(orders.findById(id).orElseThrow().getLastSagaStatusSequence()).isZero();
        processor.applyDeliveryStatus(UUID.randomUUID(), id, "WAIT_SHIPPER_CONFIRM", "sequenced", 1,
                deliveryEvent(id, "WAIT_SHIPPER_CONFIRM", null));
        UUID lateLegacy = UUID.randomUUID();
        assertThatThrownBy(() -> processor.applyDeliveryStatus(lateLegacy, id, "FINDING_SHIPPER", "late", event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Legacy Saga command arrived after sequenced commands");
        assertThat(repository.findById(lateLegacy)).isEmpty();
        assertThat(orders.findById(id).orElseThrow().getLastSagaStatusSequence()).isEqualTo(1);
    }

    private com.delivery.order_service.entity.Order pendingOrder() {
        var order = new com.delivery.order_service.entity.Order();
        order.setUserId(7L);
        order.setCreatorId(7L);
        order.setRestaurantId(8L);
        order.setStatus(com.delivery.order_service.entity.OrderStatus.PENDING);
        order.setPaymentMethod("COD");
        order.setSubtotalPrice(java.math.BigDecimal.valueOf(100000));
        order.setShippingFee(java.math.BigDecimal.valueOf(15000));
        order.setDiscountAmount(java.math.BigDecimal.ZERO);
        order.setTotalPrice(java.math.BigDecimal.valueOf(115000));
        return order;
    }

    private com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent deliveryEvent(Long id, String status, String notes) {
        var event = new com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent();
        event.setOrderId(id);
        event.setStatus(status);
        event.setNotes(notes);
        return event;
    }

    private static final class DeliberateRollback extends RuntimeException {
    }
}
