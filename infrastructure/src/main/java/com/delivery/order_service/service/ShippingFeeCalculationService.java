package com.delivery.order_service.service;

import java.math.BigDecimal;

/**
 * ✅ Service tính phí ship động theo khoảng cách
 * Formula tương tự Shopee Food/Grab
 */
public interface ShippingFeeCalculationService {
    
    /**
     * Tính phí ship dựa trên khoảng cách
     * @param pickupLat Latitude điểm lấy hàng (restaurant)
     * @param pickupLng Longitude điểm lấy hàng
     * @param deliveryLat Latitude điểm giao hàng (customer)
     * @param deliveryLng Longitude điểm giao hàng
     * @param subtotal Giá trị đơn hàng (để tính surge nếu cần)
     * @return Phí ship (VNĐ)
     */
    BigDecimal calculateShippingFee(
        Double pickupLat, 
        Double pickupLng, 
        Double deliveryLat, 
        Double deliveryLng,
        BigDecimal subtotal
    );
    
    /**
     * Tính khoảng cách giữa 2 điểm (km) sử dụng Haversine formula
     */
    double calculateDistance(
        double lat1, 
        double lng1, 
        double lat2, 
        double lng2
    );
    
}
