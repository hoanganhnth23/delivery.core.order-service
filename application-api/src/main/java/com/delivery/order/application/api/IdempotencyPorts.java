package com.delivery.order.application.api;
/** Host supplies atomic inserts/CAS and pessimistic lookup, never emulated by the workflow. */
public interface IdempotencyPorts<L> {
    void requireArguments();
    L find();
    L findLocked();
    void assertFingerprint(L receipt);
    boolean completed(L receipt);
    boolean ownedAndLive(L receipt);
    boolean live(L receipt);
    int insert();
    int reclaim();
    L requireFound();
    RuntimeException inProgress();
}
