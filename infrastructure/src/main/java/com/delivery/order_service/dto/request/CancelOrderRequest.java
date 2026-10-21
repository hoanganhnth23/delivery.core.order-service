package com.delivery.order_service.dto.request;

import lombok.Data;
import jakarta.validation.constraints.Size;

@Data
public class CancelOrderRequest {
    @Size(max = 500)
    private String reason;
}
