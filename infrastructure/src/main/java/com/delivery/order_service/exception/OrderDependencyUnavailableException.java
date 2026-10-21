package com.delivery.order_service.exception;

/**
 * Retryable failure at an Order -> dependency boundary.  This must not be
 * reported as a customer validation error: the caller should retry the same
 * idempotency key after the dependency recovers.
 */
/**
 * Extends the legacy validation base as a source-compatibility rail for
 * callers that historically treated dependency failures as validation errors.
 * GlobalExceptionHandler registers the more-specific handler and still emits
 * the correct retryable 503 response.
 */
public class OrderDependencyUnavailableException extends ValidationException {
    private final String dependency;
    private final long retryAfterSeconds;

    public OrderDependencyUnavailableException(String dependency, String message) {
        this(dependency, message, null, 2);
    }

    public OrderDependencyUnavailableException(String dependency, String message, Throwable cause) {
        this(dependency, message, cause, 2);
    }

    public OrderDependencyUnavailableException(String dependency, String message,
                                               Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.dependency = dependency == null || dependency.isBlank() ? "unknown" : dependency;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String getDependency() {
        return dependency;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
