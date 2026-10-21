package com.delivery.order_service.exception;

import com.delivery.order_service.payload.BaseResponse;
import com.delivery.order_service.payload.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * ✅ Global Exception Handler cho Order Service theo Backend Instructions
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderApiException.class)
    public ResponseEntity<BaseResponse<Object>> handleOrderApiException(OrderApiException ex) {
        log.info("Order command conflict code={}", ex.getCode());
        var response = ResponseEntity.status(HttpStatus.CONFLICT);
        if ("IDEMPOTENCY_IN_PROGRESS".equals(ex.getCode())) {
            response.header("Retry-After", "1");
        }
        return response
                .body(new BaseResponse<>(0, null, ex.getMessage(),
                        new ApiError(ex.getCode(), ex.getDetails())));
    }

    @ExceptionHandler(OrderDependencyUnavailableException.class)
    public ResponseEntity<BaseResponse<Object>> handleDependencyUnavailable(
            OrderDependencyUnavailableException ex) {
        log.warn("Order dependency unavailable: dependency={}, retryAfterSeconds={}, reason={}",
                ex.getDependency(), ex.getRetryAfterSeconds(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
                .body(new BaseResponse<>(0, null,
                        "Dịch vụ đặt hàng tạm thời chưa sẵn sàng",
                new ApiError("DEPENDENCY_UNAVAILABLE",
                                Map.of("dependency", ex.getDependency()))));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            PessimisticLockingFailureException.class,
            DeadlockLoserDataAccessException.class,
            QueryTimeoutException.class,
            CannotCreateTransactionException.class,
            TransactionTimedOutException.class})
    public ResponseEntity<BaseResponse<Object>> handleTransientDatabaseFailure(Exception ex) {
        log.warn("Order database temporarily unavailable or contended: reason={}",
                ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(new BaseResponse<>(0, null,
                        "Hệ thống đặt hàng tạm thời bận, vui lòng thử lại",
                        new ApiError("DATABASE_UNAVAILABLE", Map.of())));
    }

    /**
     * Handle custom ValidationException - Single source of validation errors
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<BaseResponse<Object>> handleValidationException(ValidationException ex) {
        log.error("🚨 Validation error: {}", ex.getMessage());
        
        BaseResponse<Object> response = new BaseResponse<>(0, null, ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Object>> handleBeanValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(new BaseResponse<>(0, null, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Object>> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new BaseResponse<>(0, null, "Request body không hợp lệ"));
    }

    /**
     * Handle ResourceNotFoundException
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<BaseResponse<Object>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.error("🔍 Resource not found: {}", ex.getMessage());
        
        BaseResponse<Object> response = new BaseResponse<>(0, null, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handle AccessDeniedException
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<Object>> handleAccessDeniedException(AccessDeniedException ex) {
        log.error("🚫 Access denied: {}", ex.getMessage());
        
        BaseResponse<Object> response = new BaseResponse<>(0, null, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Handle IllegalStateException and IllegalArgumentException
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<BaseResponse<Object>> handleIllegalStateException(RuntimeException ex) {
        log.error("⚠️ Business rule violation: {}", ex.getMessage());
        
        BaseResponse<Object> response = new BaseResponse<>(0, null, ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Object>> handleGenericException(Exception ex) {
        log.error("💥 Unexpected error occurred: {}", ex.getMessage(), ex);
        
        BaseResponse<Object> response = new BaseResponse<>(0, null, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
