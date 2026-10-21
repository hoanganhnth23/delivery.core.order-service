package com.delivery.order_service.exception;

/**
 * ✅ Custom exception cho validation errors theo Backend Instructions
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
