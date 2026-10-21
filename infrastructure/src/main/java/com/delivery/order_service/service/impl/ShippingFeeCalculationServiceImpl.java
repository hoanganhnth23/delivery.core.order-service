package com.delivery.order_service.service.impl;

import com.delivery.order.domain.ShippingPolicy;
import com.delivery.order_service.exception.ValidationException;
import com.delivery.order_service.service.ShippingFeeCalculationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Host facade; coordinates, distance pricing and rounding belong to Order domain. */
@Slf4j
@Service
public class ShippingFeeCalculationServiceImpl implements ShippingFeeCalculationService {
    @Override
    public BigDecimal calculateShippingFee(Double pickupLat, Double pickupLng,
            Double deliveryLat, Double deliveryLng, BigDecimal subtotal) {
        requireVietnamCoordinates(pickupLat, pickupLng, deliveryLat, deliveryLng);
        double distanceKm = calculateDistance(pickupLat, pickupLng, deliveryLat, deliveryLng);
        BigDecimal fee = ShippingPolicy.fee(distanceKm);
        log.info("✅ Final shipping fee: {} VNĐ for {} km", fee, String.format("%.2f", distanceKm));
        return fee;
    }

    @Override
    public double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        requireVietnamCoordinates(lat1, lng1, lat2, lng2);
        return ShippingPolicy.distance(lat1, lng1, lat2, lng2);
    }

    private void requireVietnamCoordinates(Double pickupLat, Double pickupLng,
            Double deliveryLat, Double deliveryLng) {
        if (!ShippingPolicy.validCoordinates(pickupLat, pickupLng, deliveryLat, deliveryLng)) {
            throw new ValidationException(
                    "Tọa độ lấy/giao hàng là bắt buộc và phải nằm trong phạm vi Việt Nam");
        }
    }
}
