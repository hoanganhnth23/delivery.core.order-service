package com.delivery.order_service.repository;

import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);
    
    /**
     * Tìm đơn hàng theo user ID
     */
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Order> findByUserPrincipalIdOrderByCreatedAtDesc(Long principalId, Pageable pageable);

    Page<Order> findByCreatorPrincipalIdOrderByCreatedAtDesc(Long principalId, Pageable pageable);

    @Query("select o from Order o where o.userPrincipalId = :principalId "
            + "or (o.userPrincipalId is null and o.userId = :legacyUserId) order by o.createdAt desc")
    Page<Order> findByPrincipalOrUnmigratedLegacyUserOrderByCreatedAtDesc(
            @Param("principalId") Long principalId,
            @Param("legacyUserId") Long legacyUserId,
            Pageable pageable);
    
    /**
     * Tìm đơn hàng theo restaurant ID
     */
    Page<Order> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId, Pageable pageable);

    Page<Order> findByRestaurantIdAndCreatorIdOrderByCreatedAtDesc(
            Long restaurantId, Long creatorId, Pageable pageable);
    
    /**
     * Tìm đơn hàng theo status
     */
    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);
    
    /**
     * Lấy tất cả đơn hàng
     */
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    /**
     * ✅ Tìm đơn hàng theo creator ID (chủ nhà hàng)
     * Query trực tiếp từ bảng orders mà không cần JOIN với restaurant
     */
    Page<Order> findByCreatorIdOrderByCreatedAtDesc(Long creatorId, Pageable pageable);

    @Query("select o from Order o where o.creatorPrincipalId = :principalId "
            + "or (o.creatorPrincipalId is null and o.creatorId = :legacyCreatorId) order by o.createdAt desc")
    Page<Order> findByRestaurantOwnerPrincipalOrUnmigratedLegacyOrderByCreatedAtDesc(
            @Param("principalId") Long principalId,
            @Param("legacyCreatorId") Long legacyCreatorId,
            Pageable pageable);

}
