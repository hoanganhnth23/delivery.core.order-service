package com.delivery.order_service.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ✅ Request để lấy checkout preview — server tính toán giá chính xác.
 * Client gửi danh sách items + delivery coords, server trả về giá canonical.
 */
@Getter
@Setter
public class CheckoutPreviewRequest {
    /** Optional server-validated source for pinned livestream prices. */
    private UUID livestreamId;

    @NotNull
    @Positive
    private Long restaurantId;

    @NotNull
    @DecimalMin("8.0")
    @DecimalMax("24.0")
    private Double deliveryLat;

    @NotNull
    @DecimalMin("102.0")
    @DecimalMax("110.0")
    private Double deliveryLng;

    @Size(max = 50)
    private String couponCode; // Nullable — dùng khi áp mã giảm giá

    @Positive
    private Long voucherId;

    /** Up to one voucher from each stacking layer. */
    @Size(max = 3)
    private List<@Positive Long> selectedVoucherIds;

    /** AUTO or MANUAL; kept as a string so Order remains decoupled from Promotion's DTO module. */
    private String selectionMode;

    @NotEmpty
    @Size(max = 50)
    @Valid
    private List<PreviewItem> items;

    @Getter
    @Setter
    public static class PreviewItem {
        @NotNull
        @Positive
        private Long menuItemId;

        @Positive
        private Long flashSaleItemId;

        @NotNull
        @Min(1)
        @Max(99)
        private Integer quantity;
    }
}
