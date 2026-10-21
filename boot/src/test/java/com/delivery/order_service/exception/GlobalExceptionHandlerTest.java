package com.delivery.order_service.exception;

import com.delivery.order_service.payload.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void orderApiConflictCarriesCodeDetailsAndRetryHintOnlyWhileInProgress() {
        ResponseEntity<BaseResponse<Object>> inProgress = handler.handleOrderApiException(
                new OrderApiException("IDEMPOTENCY_IN_PROGRESS", "Đang xử lý", Map.of("key", "k1")));
        assertThat(inProgress.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inProgress.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(inProgress.getBody().getStatus()).isZero();
        assertThat(inProgress.getBody().getMessage()).isEqualTo("Đang xử lý");
        assertThat(inProgress.getBody().getError().code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(inProgress.getBody().getError().details()).isEqualTo(Map.of("key", "k1"));

        ResponseEntity<BaseResponse<Object>> mismatch = handler.handleOrderApiException(
                new OrderApiException("IDEMPOTENCY_KEY_REUSED", "Khóa đã dùng"));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatch.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(mismatch.getBody().getError().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void dependencyOutageReturns503WithRetryAfterAndDependencyName() {
        ResponseEntity<BaseResponse<Object>> response = handler.handleDependencyUnavailable(
                new OrderDependencyUnavailableException("restaurant-service", "timeout", null, 7));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(response.getBody().getMessage()).isEqualTo("Dịch vụ đặt hàng tạm thời chưa sẵn sàng");
        assertThat(response.getBody().getError().code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.getBody().getError().details()).isEqualTo(Map.of("dependency", "restaurant-service"));
    }

    @Test
    void transientDatabaseFailuresReturn503WithoutLeakingCause() {
        for (Exception failure : new Exception[] {
                new QueryTimeoutException("slow"),
                new DeadlockLoserDataAccessException("deadlock", null),
                new CannotCreateTransactionException("pool exhausted") }) {
            ResponseEntity<BaseResponse<Object>> response = handler.handleTransientDatabaseFailure(failure);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
            assertThat(response.getBody().getMessage()).isEqualTo("Hệ thống đặt hàng tạm thời bận, vui lòng thử lại");
            assertThat(response.getBody().getError().code()).isEqualTo("DATABASE_UNAVAILABLE");
        }
    }

    @Test
    void validationNotFoundForbiddenAndBusinessRuleMapToTheirStatuses() {
        assertError(handler.handleValidationException(new ValidationException("Thiếu món")),
                HttpStatus.BAD_REQUEST, "Thiếu món");
        assertError(handler.handleResourceNotFoundException(new ResourceNotFoundException("Không tìm thấy đơn")),
                HttpStatus.NOT_FOUND, "Không tìm thấy đơn");
        assertError(handler.handleAccessDeniedException(new AccessDeniedException("Không có quyền")),
                HttpStatus.FORBIDDEN, "Không có quyền");
        assertError(handler.handleIllegalStateException(new IllegalStateException("Sai trạng thái")),
                HttpStatus.BAD_REQUEST, "Sai trạng thái");
        assertError(handler.handleIllegalStateException(new IllegalArgumentException("Sai tham số")),
                HttpStatus.BAD_REQUEST, "Sai tham số");
    }

    @Test
    void beanValidationJoinsDistinctFieldErrors() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "restaurantId", "must not be null"));
        binding.addError(new FieldError("request", "items", "must not be empty"));
        binding.addError(new FieldError("request", "items", "must not be empty"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("beanValidationJoinsDistinctFieldErrors"), -1);

        assertError(handler.handleBeanValidation(new MethodArgumentNotValidException(parameter, binding)),
                HttpStatus.BAD_REQUEST, "restaurantId: must not be null, items: must not be empty");
    }

    @Test
    void unreadableBodyAndUnexpectedErrorsHideInternalDetails() {
        assertError(handler.handleUnreadableRequest(new HttpMessageNotReadableException(
                        "JSON parse error at line 1", new MockHttpInputMessage(new byte[0]))),
                HttpStatus.BAD_REQUEST, "Request body không hợp lệ");
        assertError(handler.handleGenericException(new RuntimeException("SQL secret detail")),
                HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
    }

    private static void assertError(ResponseEntity<BaseResponse<Object>> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().getStatus()).isZero();
        assertThat(response.getBody().getData()).isNull();
        assertThat(response.getBody().getMessage()).isEqualTo(message);
    }
}
