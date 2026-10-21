package com.delivery.order_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Records only bounded operational labels; never pass aggregate or actor identifiers. */
@Component
public class BusinessMetrics {
    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String event) {
        Counter.builder("delivery.business.events")
                .description("Completed delivery business events")
                .tag("event", event)
                .tag("outcome", "success")
                .register(registry)
                .increment();
    }

    public void kafka(String event) {
        Counter.builder("delivery.kafka.events")
                .description("Kafka retry, DLT, and consumer error events")
                .tag("event", event)
                .register(registry)
                .increment();
    }

    /** Records use of a bounded, temporary legacy ownership compatibility path. */
    public void identityLegacyFallback(String surface) {
        Counter.builder("delivery.identity.legacy.fallback")
                .tag("service", "order")
                .tag("surface", surface)
                .register(registry)
                .increment();
    }
}
