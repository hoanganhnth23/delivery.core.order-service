package com.delivery.order.application.api;
public interface LegacyIdempotencyPorts<L> {
    L find();
    int insert();
    L requireFound();
    L requireExisting();
    void assertFingerprint(L receipt);
    boolean completed(L receipt);
    RuntimeException inProgress();
}
