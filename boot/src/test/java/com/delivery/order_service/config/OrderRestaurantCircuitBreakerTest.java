package com.delivery.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OrderRestaurantCircuitBreakerTest {
    @Test
    void opensAfterFailuresAndClosesAfterHalfOpenSuccess() {
        RestaurantCallResilienceProperties properties = shortWindowProperties();
        OrderRestaurantCircuitBreaker breaker = new OrderRestaurantCircuitBreaker(properties, new SimpleMeterRegistry());

        assertThat(breaker.execute(() -> "canonical")).isEqualTo("canonical");
        assertThatThrownBy(() -> breaker.execute(() -> { throw new IllegalStateException("restaurant 5xx"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute(() -> { throw new IllegalStateException("restaurant timeout"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> breaker.execute(() -> "not called"))
                .hasMessageContaining("circuit is open");

        breaker.circuitBreaker().transitionToHalfOpenState();
        assertThat(breaker.execute(() -> "recovered")).isEqualTo("recovered");
        assertThat(breaker.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private RestaurantCallResilienceProperties shortWindowProperties() {
        RestaurantCallResilienceProperties properties = new RestaurantCallResilienceProperties();
        properties.setSlidingWindowSize(2);
        properties.setFailureRateThreshold(100);
        properties.setPermittedHalfOpenCalls(1);
        return properties;
    }
}
