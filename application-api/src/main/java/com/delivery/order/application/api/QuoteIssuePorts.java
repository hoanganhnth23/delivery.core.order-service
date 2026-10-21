package com.delivery.order.application.api;

/** Remote preview followed by an independent, short host persistence transaction. */
public interface QuoteIssuePorts<R> {
    R preview();
    R persist(R preview);
}
