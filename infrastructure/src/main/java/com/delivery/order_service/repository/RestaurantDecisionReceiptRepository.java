package com.delivery.order_service.repository;

import com.delivery.order_service.entity.RestaurantDecisionReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RestaurantDecisionReceiptRepository
        extends JpaRepository<RestaurantDecisionReceipt, UUID> {

    Optional<RestaurantDecisionReceipt> findByOrderId(Long orderId);
}
