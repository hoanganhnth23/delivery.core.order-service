package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderOutboxTraceContextTest {
    @Test
    void persistsW3cTraceparentForTheScheduledKafkaRelay() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(context.spanId()).thenReturn("0123456789abcdef");
        when(context.sampled()).thenReturn(true);

        new OrderOutboxService(repository, new ObjectMapper(), tracer)
                .enqueue("ORDER_CREATED", "42", "order.created", "42", Map.of("orderId", 42));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTraceparent())
                .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
    }
}
