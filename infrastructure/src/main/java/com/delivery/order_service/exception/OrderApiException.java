package com.delivery.order_service.exception;

/** Public business conflict with stable machine-readable code. */
public class OrderApiException extends RuntimeException {
    private final String code;
    private final Object details;

    public OrderApiException(String code, String message) {
        this(code, message, null);
    }

    public OrderApiException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String getCode() { return code; }
    public Object getDetails() { return details; }
}
