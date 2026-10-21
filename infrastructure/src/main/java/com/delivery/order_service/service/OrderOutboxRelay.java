package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.delivery.order.contracts.OrderCreatedEvent;
import com.delivery.order_service.dto.event.OrderCancelledEvent;
import com.delivery.observability.OutboxTraceContext;
import com.delivery.observability.SafeLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxRelay.class);

    private final OutboxEventRepository repository;
    private final OrderOutboxLeaseService leaseService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutSeconds;
    private final int maxAttempts;
    private final long leaseSeconds;

    @Autowired
    public OrderOutboxRelay(
            OrderOutboxLeaseService leaseService,
            OutboxEventRepository repository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.outbox.batch-size:50}") int batchSize,
            @Value("${app.outbox.send-timeout-seconds:10}") long sendTimeoutSeconds,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.outbox.lease-seconds:30}") long leaseSeconds) {
        this(leaseService, repository, kafkaTemplate, objectMapper, batchSize,
                sendTimeoutSeconds, maxAttempts, leaseSeconds, true);
    }

    /** Source-compatible constructor for focused relay tests. */
    public OrderOutboxRelay(
            OutboxEventRepository repository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            int batchSize,
            long sendTimeoutSeconds,
            int maxAttempts) {
        this(null, repository, kafkaTemplate, objectMapper, batchSize,
                sendTimeoutSeconds, maxAttempts, 30, true);
    }

    private OrderOutboxRelay(
            OrderOutboxLeaseService leaseService,
            OutboxEventRepository repository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            int batchSize,
            long sendTimeoutSeconds,
            int maxAttempts,
            long leaseSeconds,
            boolean ignoredConstructorMarker) {
        this.leaseService = leaseService;
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.sendTimeoutSeconds = Math.max(1, Math.min(sendTimeoutSeconds, 60));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
        this.leaseSeconds = Math.max(5, Math.min(leaseSeconds, 300));
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void relayPendingEvents() {
        if (leaseService != null) {
            relayLeasedEvents();
            return;
        }

        // Compatibility rail for focused unit tests and older manually wired
        // instances. Spring production wiring uses the lease path above.
        List<OutboxEvent> events = repository.lockNextBatch(batchSize);
        if (events == null) {
            // Mockito's unstubbed collection methods return null; production
            // Spring Data repositories return an empty list. Keep the fallback
            // solely for source-compatible focused tests.
            events = List.of();
        }
        for (OutboxEvent event : events) {
            relayLegacyEvent(event);
        }
    }

    /**
     * Claim and publish one event at a time. A single lease token shared by a
     * large sequential batch can expire while later Kafka sends are waiting;
     * that creates avoidable duplicate publishes when another relay reclaims
     * the rows. Each event gets a fresh lease budget, while the poll still
     * honours the configured batch-size as a maximum amount of work.
     */
    private void relayLeasedEvents() {
        // KafkaTemplate.send itself may wait up to producer max.block.ms before
        // returning a Future; budget that time in addition to Future.get().
        long eventLeaseSeconds = Math.max(leaseSeconds, sendTimeoutSeconds + 10);
        for (int processed = 0; processed < batchSize; processed++) {
            UUID leaseToken = UUID.randomUUID();
            List<OutboxEvent> events;
            try {
                events = leaseService.claim(1, leaseToken, eventLeaseSeconds);
            } catch (RuntimeException claimFailure) {
                log.warn("Order outbox claim failed; relay will retry on next poll: reason={}",
                        claimFailure.getClass().getSimpleName());
                return;
            }
            if (events == null || events.isEmpty()) {
                return;
            }
            // claim(1) is a production invariant. Limiting defensively keeps a
            // faulty repository implementation from processing a batch under a
            // lease whose lifetime was calculated for one event.
            try {
                relayLeasedEvent(events.get(0), leaseToken);
                if (Thread.currentThread().isInterrupted()) return;
            } catch (RuntimeException updateFailure) {
                // A temporary DB outage while marking the result must not stop
                // the scheduler. The lease expiry is the recovery mechanism.
                log.warn("Order outbox state update failed; event will be reclaimed after lease expiry: eventId={}",
                        events.get(0).getEventId(), updateFailure.getClass().getSimpleName());
            }
        }
    }

    private void relayLeasedEvent(OutboxEvent event, UUID leaseToken) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    event.getTopic(), event.getEventKey(), typedPayload(event, payload));
            record.headers().add("eventId", bytes(event.getEventId().toString()));
            record.headers().add("eventType", bytes(event.getEventType()));
            record.headers().add("aggregateId", bytes(event.getAggregateId()));
            record.headers().add("X-Correlation-Id", bytes(event.getCorrelationId()));
            try (Scope ignored = OutboxTraceContext.open(event.getTraceparent())) {
                kafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception failure) {
            safeMarkFailure(event, leaseToken, failure);
            return;
        }
        try {
            if (!leaseService.markSent(event.getId(), leaseToken)) {
                log.warn("Order outbox lease was lost after successful publish: eventId={}",
                        event.getEventId());
            }
        } catch (RuntimeException stateFailure) {
            log.warn("Order outbox state update failed after successful publish; event will be reclaimed: eventId={}, reason={}",
                    event.getEventId(), stateFailure.getClass().getSimpleName());
        }
    }

    private void safeMarkFailure(OutboxEvent event, UUID leaseToken, Exception failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!leaseService.markFailure(event.getId(), leaseToken, failure, maxAttempts)) {
                log.warn("Order outbox lease was lost after publish failure: eventId={}", event.getEventId());
            }
        } catch (RuntimeException stateFailure) {
            log.warn("Order outbox state update failed after publish failure; event will be reclaimed: eventId={}, reason={}",
                    event.getEventId(), stateFailure.getClass().getSimpleName());
        }
    }

    private void relayLegacyEvent(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    event.getTopic(), event.getEventKey(), typedPayload(event, payload));
            record.headers().add("eventId", bytes(event.getEventId().toString()));
            record.headers().add("eventType", bytes(event.getEventType()));
            record.headers().add("aggregateId", bytes(event.getAggregateId()));
            record.headers().add("X-Correlation-Id", bytes(event.getCorrelationId()));
            try (Scope ignored = OutboxTraceContext.open(event.getTraceparent())) {
                kafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            }
            event.setStatus(OutboxEvent.Status.SENT);
            event.setSentAt(LocalDateTime.now());
            event.setLastError(null);
            repository.save(event);
        } catch (Exception failure) {
            recordLegacyFailure(event, failure);
            repository.save(event);
        }
    }

    private void recordLegacyFailure(OutboxEvent event, Exception failure) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(abbreviate(SafeLog.exceptionMessage(failure)));
        if (attempts >= maxAttempts) {
            event.setStatus(OutboxEvent.Status.DEAD);
            log.error("Order outbox delivery failed: eventId={}, orderId={}, attempts={}, reason={}",
                    event.getEventId(), event.getAggregateId(), attempts, failure.getClass().getSimpleName());
        } else {
            long delaySeconds = Math.min(300L, 1L << Math.min(Math.max(attempts - 1, 0), 8));
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
            log.warn("Order outbox retry scheduled: eventId={}, orderId={}, attempt={}, delaySeconds={}, reason={}",
                    event.getEventId(), event.getAggregateId(), attempts, delaySeconds,
                    failure.getClass().getSimpleName());
        }
    }

    private byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The outbox stores JSON so that enqueueing remains transactional. Rehydrate
     * published business events before handing them to the shared KafkaTemplate;
     * this lets JsonSerializer emit the order-contracts type header consumed by
     * platform/kafka-starter without changing the stored payload or topic/key.
     */
    private Object typedPayload(OutboxEvent event, JsonNode payload) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            return objectMapper.convertValue(payload, OrderCreatedEvent.class);
        }
        if ("ORDER_CANCELLED".equals(event.getEventType())
                || "REFUND_ELIGIBLE".equals(event.getEventType())) {
            return objectMapper.convertValue(payload, OrderCancelledEvent.class);
        }
        return payload;
    }

    private String abbreviate(String message) {
        if (message == null) {
            return "Unknown Kafka publish failure";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
