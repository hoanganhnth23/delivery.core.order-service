package com.delivery.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ✅ Response chứa giá tính toán bởi server.
 * Client hiển thị thông tin này trên màn Checkout thay vì tính giá local.
 */
@Getter
@Setter
@Builder
public class CheckoutPreviewResponse {

    /** Server-issued confirmation of this exact calculated checkout price. */
    private UUID quoteId;
    private Instant expiresAt;

    private Long restaurantId;
    private String restaurantName;

    /** Additive public ETA metadata; omitted while the capability is disabled. */
    private Integer etaMinMinutes;
    private Integer etaMaxMinutes;
    private String etaSource;
    private Long serviceabilityZoneId;
    private Long serviceabilityZoneRevision;

    private List<PreviewItemDetail> items;

    private BigDecimal subtotal;        // Tổng tiền hàng (server tính)
    private BigDecimal shippingFee;     // Phí ship (server tính theo khoảng cách)
    private BigDecimal discountAmount;  // Giảm giá (từ coupon nếu có)
    private BigDecimal totalPrice;      // subtotal + shippingFee - discountAmount

    private BigDecimal itemDiscount;
    private BigDecimal shippingDiscount;
    private BigDecimal customerShippingFee;
    private BigDecimal grossShippingFee;
    private BigDecimal platformSubsidy;
    private BigDecimal shopDiscount;

    private String couponCode;          // Coupon đã áp dụng (null nếu không có)
    private String couponMessage;       // "Giảm 20k" hoặc "Mã không hợp lệ"
    private Long voucherId;
    private List<Long> selectedVoucherIds;
    private String selectionMode;
    private List<AppliedVoucherInfo> appliedVouchers;

    private List<PriceChangeInfo> priceChanges;     // Danh sách giá thay đổi
    private List<Long> unavailableItemIds;           // Món đã hết hàng

    @Getter
    @Setter
    @Builder
    public static class AppliedVoucherInfo {
        private Long voucherId;
        private String code;
        private String layer;
        private String fundingSource;
        private BigDecimal discountBase;
        private BigDecimal discountAmount;
    }

    @Getter
    @Setter
    @Builder
    public static class PreviewItemDetail {
        private Long menuItemId;
        private String menuItemName;
        private String imageUrl;
        private BigDecimal unitPrice;   // Giá server canonical
        private Integer quantity;
        private BigDecimal lineTotal;   // unitPrice * quantity
    }

    @Getter
    @Setter
    @Builder
    public static class PriceChangeInfo {
        private Long menuItemId;
        private String menuItemName;
        private BigDecimal oldPrice;    // Giá client gửi lên (nếu có)
        private BigDecimal newPrice;    // Giá server canonical
    }
}
