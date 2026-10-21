package com.delivery.order.application.api;
import java.util.UUID;
import java.util.function.Supplier;
/** Request-scoped adapters; opaque handles never escape the use case. */
public interface CreateOrderPorts<R, P, L> {
    void admit();
    boolean hasIdempotencyKey();
    String fingerprint();
    UUID newToken();
    L acquire(String fingerprint, UUID token);
    Long completedOrder(L lease);
    R replay(Long orderId);
    P prepare();
    R transaction(Supplier<R> operation);
    R persist(P prepared, String fingerprint, UUID token);
    void release(L lease, UUID token);
}
