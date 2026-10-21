package com.delivery.order.application;
import com.delivery.order.application.api.CreateWritePorts;
/** Runs inside the host transaction; receipt locking must precede shell insertion. */
public final class CreateWriteWorkflow {
    private CreateWriteWorkflow() {}
    public static <L, S, R> R execute(CreateWritePorts<L, S, R> ports) {
        L receipt = null;
        if (ports.hasKey()) {
            receipt = ports.claim();
            Long orderId = ports.completedOrder(receipt);
            if (orderId != null) return ports.replay(orderId);
        }
        S shell = ports.flushShell();
        return ports.reserveAndSnapshot(shell, receipt);
    }
}
