package com.delivery.order_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderResponse {

    private Long id;
    private Long userId;
    private Long restaurantId;
    private String restaurantName;
    private String restaurantAddress;
    private String restaurantPhone;
    private Double restaurantLat;
    private Double restaurantLng;
    private Long shipperId;
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
    private UUID promotionReservationId;
    private List<PromotionBreakdown> appliedVouchers;
    private String status;
    private String paymentMethod;
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;
    private Double pickupLat;
    private Double pickupLng;
    private String customerName;
    private String customerPhone;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long creatorId; // ID của chủ nhà hàng (restaurant owner)
    private String cancelReason;
    private List<OrderItemResponse> items;

    @Getter
    @Setter
    public static class PromotionBreakdown {
        private Long voucherId;
        private String code;
        private String layer;
        private String fundingSource;
        private BigDecimal discountBase;
        private BigDecimal discountAmount;
    }


}
