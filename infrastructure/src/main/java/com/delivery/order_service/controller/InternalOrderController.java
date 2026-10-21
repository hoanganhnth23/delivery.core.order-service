package com.delivery.order_service.controller;

import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.payload.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/orders/internal")
@RequiredArgsConstructor
public class InternalOrderController {


    private final OrderRepository orderRepository;

    @Value("${app.internal.secret:}")
    private String internalSecret;

    @GetMapping("/{orderId}/rating-eligibility")
    public ResponseEntity<BaseResponse<Boolean>> isRatingEligible(
            @PathVariable Long orderId,
            @RequestParam Long userId,
            @RequestParam Long restaurantId,
            @RequestHeader(value = "Internal-Token", required = false) String internalToken) {
        if (internalSecret == null || internalSecret.isBlank()
                || !internalSecret.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new BaseResponse<>(0, null, "Forbidden"));
        }

        boolean eligible = orderRepository.findById(orderId)
                .filter(order -> userId.equals(order.getUserId()))
                .filter(order -> restaurantId.equals(order.getRestaurantId()))
                .filter(order -> order.getStatus() == OrderStatus.DELIVERED)
                .isPresent();
        return ResponseEntity.ok(new BaseResponse<>(1, eligible));
    }

    @GetMapping("/{orderId}/restaurant-decision-eligibility")
    @Transactional
    public ResponseEntity<BaseResponse<Boolean>> isRestaurantDecisionEligible(
            @PathVariable Long orderId,
            @RequestParam Long restaurantId,
            @RequestHeader(value = "Internal-Token", required = false) String internalToken) {
        if (internalSecret == null || internalSecret.isBlank()
                || !internalSecret.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new BaseResponse<>(0, null, "Forbidden"));
        }

        boolean eligible = orderRepository.findByIdForUpdate(orderId)
                .filter(order -> restaurantId.equals(order.getRestaurantId()))
                .filter(order -> order.getStatus() == OrderStatus.PENDING)
                .isPresent();
        return ResponseEntity.ok(new BaseResponse<>(1, eligible));
    }
}
