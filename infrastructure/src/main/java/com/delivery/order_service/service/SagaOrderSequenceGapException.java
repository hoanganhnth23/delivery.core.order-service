package com.delivery.order_service.service;

/** Retryable: a same-order command reached Order before its predecessor. */
public class SagaOrderSequenceGapException extends RuntimeException {
    public SagaOrderSequenceGapException(Long orderId, long expected, long actual) {
        super("Saga status sequence gap for orderId=" + orderId + ": expected=" + expected + ", actual=" + actual);
    }
}
