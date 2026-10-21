package com.delivery.order_service.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ✅ Event DTO được gửi qua Kafka khi order bị hủy theo Backend Instructions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    private Integer schemaVersion = 2;

    // Filled by the transactional outbox, then retained during relay rehydration.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String eventType;
    // Preserve the stored JSON representation rather than reformatting time.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode occurredAt;
    
    // Order basic info
    private Long orderId;
    private Long userId;
    private Long userPrincipalId;
    private Long restaurantId;
    private String previousStatus;
    private String currentStatus; // CANCELLED
    
    // Cancellation info
    private String cancelReason;
    private Long cancelledBy; // userId who cancelled
    /** CUSTOMER, RESTAURANT, ADMIN or SYSTEM; used by refund eligibility rules. */
    private String cancelledBySource;
    /** Stable business reason code, not a free-form UI message. */
    private String cancelReasonCode;
    private LocalDateTime cancelledAt;
    
    // Delivery related
    private Long shipperId; // null if no shipper assigned
    private Boolean hasActiveDelivery;
    private UUID voucherReservationId;
    private UUID promotionReservationId;
    private UUID flashSaleReservationId;
    private UUID inventoryReservationId;

    // Immutable monetary snapshot used by settlement/refund consumers.
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
    
    // Items
    private java.util.List<java.util.Map<String, Object>> items;
    private java.util.List<java.util.Map<String, Object>> appliedVouchers;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
