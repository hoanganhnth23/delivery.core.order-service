package com.delivery.order_service.dto.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dữ liệu nhà hàng đã được restaurant-service xác thực.
 * Tất cả các trường đều lấy từ server (Redis cache), không tin tưởng client.
 */
public record ValidatedOrderData(
                Long creatorId,
                Long creatorPrincipalId,
                String restaurantName,
                String restaurantAddress,
                String restaurantPhone,
                Double pickupLat,
                Double pickupLng,
                List<ValidatedItemData> items) {

        public record ValidatedItemData(Long menuItemId, String menuItemName, BigDecimal price) {
        }
}
