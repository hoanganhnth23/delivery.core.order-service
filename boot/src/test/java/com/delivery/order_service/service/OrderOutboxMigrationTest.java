package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.entity.RestaurantDecisionReceipt;
import com.delivery.order_service.entity.SagaCommandReceipt;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.delivery.order_service.repository.RestaurantDecisionReceiptRepository;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_outbox_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.clean-disabled=true",
        "app.outbox.relay-enabled=false",
        "restaurant.service.url=http://restaurant-service"
})
@ActiveProfiles("test")
class OrderOutboxMigrationTest {

    @Autowired OutboxEventRepository repository;
    @Autowired RestaurantDecisionReceiptRepository restaurantDecisionReceiptRepository;
    @Autowired SagaCommandReceiptRepository sagaCommandReceiptRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void flywayMigrationAloneCreatesSchemaCompatibleWithEntity() {
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> repository.save(event(eventId)));

        OutboxEvent stored = repository.findAll().get(0);
        assertThat(stored.getEventId()).isEqualTo(eventId);
        assertThat(stored.getAggregateType()).isEqualTo("ORDER");
        assertThat(stored.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(stored.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
    }

    @Test
    void flywayMigrationCreatesRestaurantDecisionReceiptConstraints() {
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
                restaurantDecisionReceiptRepository.saveAndFlush(RestaurantDecisionReceipt.builder()
                        .eventId(eventId)
                        .orderId(42L)
                        .restaurantId(7L)
                        .decision("CONFIRMED")
                        .payloadFingerprint("a".repeat(64))
                        .createdAt(LocalDateTime.now())
                        .build()));

        RestaurantDecisionReceipt stored = restaurantDecisionReceiptRepository.findById(eventId).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(42L);
        assertThat(stored.getRestaurantId()).isEqualTo(7L);
        assertThat(stored.getDecision()).isEqualTo("CONFIRMED");
    }

    @Test
    void flywayMigrationCreatesSagaCommandReceiptIdentityFence() {
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
                sagaCommandReceiptRepository.saveAndFlush(new SagaCommandReceipt(
                        eventId, "UPDATE_ORDER_STATUS", 42L, "FINDING_SHIPPER", "a".repeat(64))));

        SagaCommandReceipt stored = sagaCommandReceiptRepository.findById(eventId).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(42L);
        assertThat(stored.getSagaStatus()).isEqualTo("FINDING_SHIPPER");
        assertThat(stored.getPayloadFingerprint()).hasSize(64);
    }

    private OutboxEvent event(UUID eventId) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("ORDER");
        event.setAggregateId("42");
        event.setEventType("ORDER_CREATED");
        event.setTopic("order.created");
        event.setEventKey("42");
        event.setPayload("{\"eventId\":\"" + eventId + "\",\"orderId\":42}");
        event.setStatus(OutboxEvent.Status.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setCreatedAt(LocalDateTime.now());
        return event;
    }
}
