package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

/**
 * ✅ Event DTO được gửi qua Kafka khi order được tạo theo Backend Instructions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Integer schemaVersion = 2;
    
    // Order basic info
    private Long orderId;
    private Long userId;
    private Long userPrincipalId;
    private Long restaurantId;
    private String status;
    
    // Financial info
    private BigDecimal subtotalPrice;
    private BigDecimal discountAmount;
    private BigDecimal shippingFee;
    private BigDecimal totalPrice;
    private BigDecimal itemDiscount;
    private BigDecimal shippingDiscount;
    private BigDecimal customerShippingFee;
    private BigDecimal grossShippingFee;
    private BigDecimal platformSubsidy;
    private BigDecimal shopDiscount;
    private String paymentMethod;
    
    // Delivery location info
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;
    
    // Pickup location info (restaurant coordinates)
    private Double pickupLat;
    private Double pickupLng;
    
    // Restaurant info
    private String restaurantName;
    private String restaurantAddress;
    private String restaurantPhone;
    
    // Customer info
    private String customerName;
    private String customerPhone;
    private String notes;
    
    // Timestamps
    private LocalDateTime createdAt;
    private Long creatorId;
    private Long creatorPrincipalId;
    private UUID voucherReservationId;
    private UUID promotionReservationId;
    private UUID flashSaleReservationId;
    private UUID inventoryReservationId;
    /** Immutable line snapshot from Order.order_items for downstream projections. */
    private java.util.List<java.util.Map<String, Object>> items;
    private java.util.List<java.util.Map<String, Object>> appliedVouchers;
    /** Server-owned context; absent old records are interpreted as REAL by consumers. */
    private SimulationContext simulationContext;
    
    // Event metadata
    private String eventType = "ORDER_CREATED";
    private LocalDateTime eventTimestamp = LocalDateTime.now();
}
