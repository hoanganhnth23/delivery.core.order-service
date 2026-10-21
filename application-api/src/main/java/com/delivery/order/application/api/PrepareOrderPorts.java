package com.delivery.order.application.api;
/** Preflight reads and remote calls; no write transaction belongs here. */
public interface PrepareOrderPorts<Q, V, P> {
    void admitSelections();
    boolean hasQuote();
    Q validateQuote();
    V canonicalFacts();
    void requireCanonical(V facts);
    P prepared(V facts, Q quote);
}
