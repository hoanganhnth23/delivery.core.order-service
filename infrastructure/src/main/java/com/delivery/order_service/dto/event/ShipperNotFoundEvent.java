package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ✅ Event nhận từ match-service khi không tìm được shipper
 * Dùng để cập nhật trạng thái order thành SHIPPER_NOT_FOUND
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShipperNotFoundEvent {
    
    private Long deliveryId;
    private Long orderId;
    private String reason;
    private LocalDateTime occurredAt;
    private Integer retryAttempts;
    private Double searchRadius;
    private Double pickupLat;
    private Double pickupLng;
    private Double deliveryLat;
    private Double deliveryLng;
}
