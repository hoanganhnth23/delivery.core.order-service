package com.delivery.order.application.api;
public interface CreateWritePorts<L, S, R> {
    boolean hasKey();
    L claim();
    Long completedOrder(L receipt);
    R replay(Long orderId);
    S flushShell();
    R reserveAndSnapshot(S shell, L receipt);
}
