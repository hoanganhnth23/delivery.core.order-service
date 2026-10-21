package com.delivery.order_service.service;

import com.delivery.order_service.dto.event.ShipperNotFoundEvent;
import com.delivery.order_service.dto.request.CreateOrderRequest;
import com.delivery.order_service.dto.response.OrderResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

public interface OrderService {
    
    /**
     * Tạo đơn hàng mới
     */
    OrderResponse createOrder(CreateOrderRequest request, Long userId, String role);

    OrderResponse createOrder(CreateOrderRequest request, Long principalId, Long legacyUserId, String role);

    OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKey,
                              Long principalId, Long legacyUserId, String role);

    OrderResponse createOrder(CreateOrderRequest request, UUID idempotencyKey,
                              Long principalId, Long legacyUserId, String role,
                              SimulationContext simulationContext);
    
    /**
     * Lấy thông tin đơn hàng theo ID
     */
    OrderResponse getOrderById(Long id, Long userId, String role);

    OrderResponse getOrderById(Long id, Long principalId, Long legacyUserId, String role);

    /**
     * Lấy danh sách đơn hàng của user
     */
    Page<OrderResponse> getOrdersByUser(Long userId, Long requesterId, String role, Pageable pageable);

    Page<OrderResponse> getOrdersByPrincipal(Long principalId, Long legacyUserId, String role, Pageable pageable);

    /**
     * Lấy đơn hàng của restaurant owner bằng principal, với legacy profile-ID fallback.
     */
    Page<OrderResponse> getOrdersByRestaurantOwner(Long principalId, Long legacyOwnerId, String role,
            Pageable pageable);

    Page<OrderResponse> getOrdersByRestaurant(Long restaurantId, Pageable pageable);

    /**
     * Lấy đơn hàng theo trạng thái
     */
    Page<OrderResponse> getOrdersByStatus(String status, Long userId, String role, Pageable pageable);

    /**
     * Lấy tất cả đơn hàng (Admin only)
     */
    Page<OrderResponse> getAllOrders(Long userId, String role, Pageable pageable);

    /**
     * Hủy đơn hàng
     */
    OrderResponse cancelOrder(Long orderId, Long userId, String role, String reason);

    OrderResponse cancelOrder(Long orderId, Long principalId, Long legacyUserId, String role, String reason);
    
    /**
     * ✅ Cập nhật order status khi không tìm được shipper
     */
    void updateOrderStatusFromShipperNotFoundEvent(ShipperNotFoundEvent event);

}
