package com.delivery.order_service.payload;

public class BaseResponse<T> {
    private final int status;      // 1 = success, 0 = failure
    private final String message;  // Success/error message
    private final T data;          // Response data
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private final ApiError error;
    
    public BaseResponse(int status, T data, String message) {
        this.status = status;
        this.message = message;
        this.data = data;
        this.error = null;
    }

    public BaseResponse(int status, T data, String message, ApiError error) {
        this.status = status;
        this.message = message;
        this.data = data;
        this.error = error;
    }
    
    public BaseResponse(int status, T data) {
        this.status = status;
        this.data = data;
        this.message = status == 1 ? "Thành công" : "Thất bại";
        this.error = null;
    }
    
    public int getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public T getData() {
        return data;
    }

    public ApiError getError() {
        return error;
    }
}
