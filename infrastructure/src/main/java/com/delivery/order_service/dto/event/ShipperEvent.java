package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.delivery.identity.contracts.SimulationContext;

/**
 * ✅ Shipper Event DTO theo AI Coding Instructions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShipperEvent {
    
    private Long shipperId;
    private Long deliveryId;
    private Long orderId;
    private String action; // ACCEPTED, REJECTED
    private String notes;
    private String rejectReason; // For rejection cases
    private LocalDateTime responseTime;
    private Double estimatedPickupTime; // in minutes for accepted orders
    private Double currentLat;
    private Double currentLng;
    private SimulationContext simulationContext;
}
