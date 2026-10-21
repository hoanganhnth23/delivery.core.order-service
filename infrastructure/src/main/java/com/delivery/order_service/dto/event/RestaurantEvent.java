package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ✅ Restaurant Event DTO theo AI Coding Instructions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantEvent {

    private UUID eventId;
    
    private Long restaurantId;
    private Long actorUserId;
    private Long orderId;
    private String status; // CONFIRMED, REJECTED, PREPARING, READY
    private String action; // CONFIRM, REJECT, START_PREPARATION, MARK_READY
    private Integer estimatedPrepTime; // in minutes
    private String rejectionReason;
    private LocalDateTime processedAt;
    private String notes;
}
