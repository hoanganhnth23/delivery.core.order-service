package com.delivery.order_service.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateOrderRequest {

    /** Required once quote enforcement is enabled; never supplied by a client as price authority. */
    private UUID quoteId;

    /** Optional server-validated source for pinned livestream prices. */
    private UUID livestreamId;

    private Long restaurantId;
    private String restaurantName;
    private String restaurantAddress;
    private String restaurantPhone;
    private String deliveryAddress;
    private Double deliveryLat;
    private Double deliveryLng;
    private String customerName;
    private String customerPhone;
    private String paymentMethod; // COD or ONLINE
    private String notes;
    private Double pickupLat;
    private Double pickupLng;
    private List<Long> voucherIds;
    private String selectionMode;
    private List<OrderItemRequest> items;

    @Setter
    @Getter
    public static class OrderItemRequest {
        private Long menuItemId;
        private Long flashSaleItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal price;
        private String notes;
    }
}
