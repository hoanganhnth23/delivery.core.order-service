package com.delivery.order_service.config;

import com.delivery.order_service.exception.OrderDependencyUnavailableException;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;

/** Isolates only outbound Order -> Restaurant HTTP calls. */
@Component
public class OrderRestaurantCircuitBreaker {
    private final CircuitBreaker circuitBreaker;
    private final Duration timeout;
    private final Semaphore concurrency;
    private final MeterRegistry meterRegistry;

    public OrderRestaurantCircuitBreaker(RestaurantCallResilienceProperties properties, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        timeout = Duration.ofMillis(Math.max(100, Math.min(properties.getTimeoutMs(), 30_000)));
        concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrentCalls()), true);
        circuitBreaker = CircuitBreaker.of("orderRestaurantService", CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getFailureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(properties.getWaitOpenSeconds()))
                .permittedNumberOfCallsInHalfOpenState(properties.getPermittedHalfOpenCalls())
                // Catalog/business 400/409/422 responses are client outcomes,
                // not evidence that restaurant-service is unhealthy. Counting
                // them would open the circuit during a burst of bad carts and
                // then reject valid customers with 503.
                .recordException(error -> !isBusinessClientError(error))
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event -> meterRegistry.counter(
                "delivery.circuit.state_transition", "circuit", "orderRestaurantService",
                "transition", event.getStateTransition().name()).increment());
    }

    public <T> T execute(Supplier<T> supplier) {
        if (!concurrency.tryAcquire()) {
            meterRegistry.counter("delivery.bulkhead.rejected", "dependency", "restaurant-service").increment();
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Restaurant service đang quá tải, vui lòng thử lại");
        }
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (CallNotPermittedException exception) {
            throw new OrderDependencyUnavailableException("restaurant-service",
                    "Restaurant service circuit is open", exception, 5);
        } finally {
            concurrency.release();
        }
    }

    CircuitBreaker circuitBreaker() { return circuitBreaker; }
    public Duration timeout() { return timeout; }

    private boolean isBusinessClientError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WebClientResponseException response
                    && (response.getStatusCode().value() == 400
                    || response.getStatusCode().value() == 409
                    || response.getStatusCode().value() == 422)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
