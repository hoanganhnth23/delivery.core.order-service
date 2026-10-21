package com.delivery.order.application.api;
public interface QuotePorts<Q, P, R> {
    Q find(boolean lock);
    void validate(Q quote);
    P previewInput();
    R reprice(P input);
    boolean priceChanged(Q quote, R current);
    R replacement(P input, R current);
    RuntimeException changed(R replacement);
    void admitConsume(Q quote);
    void consume(Q quote);
}
