package com.delivery.order.application;
import com.delivery.order.application.api.CreateOrderPorts;
import java.util.UUID;
/** Lease before remote preflight, followed by a separately fenced write transaction. */
public final class CreateOrderWorkflow {
    private CreateOrderWorkflow() {}
    public static <R, P, L> R execute(CreateOrderPorts<R, P, L> ports) {
        ports.admit();
        String fingerprint = null;
        UUID token = null;
        L lease = null;
        if (ports.hasIdempotencyKey()) {
            fingerprint = ports.fingerprint();
            token = ports.newToken();
            lease = ports.acquire(fingerprint, token);
            Long orderId = ports.completedOrder(lease);
            if (orderId != null) return ports.replay(orderId);
        }
        try {
            P prepared = ports.prepare();
            String finalFingerprint = fingerprint;
            UUID finalToken = token;
            return ports.transaction(() -> ports.persist(prepared, finalFingerprint, finalToken));
        } catch (RuntimeException failure) {
            if (lease != null && token != null && ports.completedOrder(lease) == null) {
                try { ports.release(lease, token); }
                catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            }
            throw failure;
        }
    }
}
