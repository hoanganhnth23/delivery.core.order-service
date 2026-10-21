package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.delivery.identity.contracts.SimulationContext;

/**
 * ✅ Delivery Status Updated Event DTO theo AI Coding Instructions
 * ✅ Updated để match với format từ Delivery Service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryStatusUpdatedEvent {
    
    private Long deliveryId;
    private Long orderId; // ✅ Sử dụng để cập nhật order status
    private Long shipperId;
    private String status; // ASSIGNED, IN_PROGRESS, DELIVERED, CANCELLED
    private String previousStatus;
    private String newStatus; // ✅ Alias cho status để match với Delivery Service
    private String oldStatus; // ✅ Alias cho previousStatus để match với Delivery Service
    private LocalDateTime updatedAt;
    private LocalDateTime timestamp; // ✅ Match với Delivery Service format
    private String eventType; // ✅ Match với Delivery Service format
    private String notes;
    private Double currentLat;
    private Double currentLng;
    private LocalDateTime estimatedDeliveryTime;
    private SimulationContext simulationContext;
    
    // ✅ Getter methods để handle cả 2 format
    public String getStatus() {
        return status != null ? status : newStatus;
    }
    
    public String getPreviousStatus() {
        return previousStatus != null ? previousStatus : oldStatus;
    }
}
