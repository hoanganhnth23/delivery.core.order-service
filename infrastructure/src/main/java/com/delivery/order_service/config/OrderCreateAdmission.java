package com.delivery.order_service.config;

import com.delivery.order_service.exception.OrderDependencyUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Bounds the number of create-order workflows admitted by one Order instance.
 * This is intentionally separate from the Restaurant HTTP bulkhead: it also
 * protects local DB connections and CPU while a remote preflight is slow.
 */
@Component
public class OrderCreateAdmission {
    private final Semaphore permits;
    private final MeterRegistry meterRegistry;

    public OrderCreateAdmission(
            @Value("${app.order.max-concurrent-creates:16}") int maxConcurrentCreates,
            MeterRegistry meterRegistry) {
        this.permits = new Semaphore(Math.max(1, Math.min(maxConcurrentCreates, 200)), true);
        this.meterRegistry = meterRegistry;
    }

    public <T> T execute(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            meterRegistry.counter("delivery.order.create.admission_rejected").increment();
            throw new OrderDependencyUnavailableException("order-service",
                    "Order service đang quá tải, vui lòng thử lại", null, 1);
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
