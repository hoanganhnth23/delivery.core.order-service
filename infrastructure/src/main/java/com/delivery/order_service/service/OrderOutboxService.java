package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.delivery.observability.CorrelationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OrderOutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OrderOutboxService(OutboxEventRepository repository, ObjectMapper objectMapper, Tracer tracer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String eventType, String aggregateId, String topic, String key, Object payload) {
        UUID eventId = UUID.randomUUID();
        append(eventId, eventType, aggregateId, topic, key, payload);
        return eventId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String aggregateId, String topic, String key, Object payload) {
        append(eventId, "ORDER_EVENT", aggregateId, topic, key, payload);
    }

    private void append(UUID eventId, String eventType, String aggregateId,
                        String topic, String key, Object payload) {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("ORDER");
        event.setAggregateId(requireText(aggregateId, "aggregateId"));
        event.setEventType(requireText(eventType, "eventType"));
        event.setTopic(requireText(topic, "topic"));
        event.setEventKey(requireText(key, "key"));
        event.setPayload(serialize(eventId, eventType, payload, now));
        event.setTraceparent(currentTraceparent());
        event.setCorrelationId(CorrelationContext.currentOrCreate());
        event.setStatus(OutboxEvent.Status.PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        repository.save(event);
    }

    private String serialize(UUID eventId, String eventType, Object payload, LocalDateTime occurredAt) {
        JsonNode tree = objectMapper.valueToTree(payload);
        if (!(tree instanceof ObjectNode objectPayload)) {
            throw new IllegalArgumentException("Outbox payload must serialize to a JSON object");
        }
        objectPayload.put("eventId", eventId.toString());
        objectPayload.put("eventType", eventType);
        if (!objectPayload.hasNonNull("occurredAt")) {
            objectPayload.put("occurredAt", occurredAt.toString());
        }
        return objectPayload.toString();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) return null;
        return "00-" + span.context().traceId() + "-" + span.context().spanId()
                + "-" + (Boolean.TRUE.equals(span.context().sampled()) ? "01" : "00");
    }
}
