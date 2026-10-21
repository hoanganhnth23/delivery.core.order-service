package com.delivery.order_service.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {
    @Test
    void recordsOnlyTheBoundedBusinessEventTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.record("order_created");

        assertThat(registry.get("delivery.business.events")
                .tag("event", "order_created")
                .tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
    }
}
