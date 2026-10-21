package com.delivery.order_service.repository;

import com.delivery.order_service.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    
    /**
     * Tìm items theo order ID
     */
    List<OrderItem> findByOrderId(Long orderId);
    
    /**
     * Xóa items theo order ID
     */
    void deleteByOrderId(Long orderId);
}
