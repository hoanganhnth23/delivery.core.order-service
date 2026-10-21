package com.delivery.order_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import com.delivery.identity.contracts.SimulationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_principal_id")
    private Long userPrincipalId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "shipper_id")
    private Long shipperId; // Updated by Delivery Service

    @Column(name = "subtotal_price", precision = 12, scale = 2)
    private BigDecimal subtotalPrice;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "shipping_fee", precision = 12, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total_price", precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "voucher_reservation_id", unique = true)
    private UUID voucherReservationId;

    @Column(name = "flash_sale_reservation_id", unique = true)
    private UUID flashSaleReservationId;

    @Column(name = "promotion_reservation_id", unique = true)
    private UUID promotionReservationId;

    /** Restaurant-owned menu inventory hold/commit identity. */
    @Column(name = "inventory_reservation_id", unique = true)
    private UUID inventoryReservationId;

    @Column(name = "item_discount", precision = 12, scale = 2)
    private BigDecimal itemDiscount;

    @Column(name = "shipping_discount", precision = 12, scale = 2)
    private BigDecimal shippingDiscount;

    @Column(name = "customer_shipping_fee", precision = 12, scale = 2)
    private BigDecimal customerShippingFee;

    @Column(name = "gross_shipping_fee", precision = 12, scale = 2)
    private BigDecimal grossShippingFee;

    @Column(name = "platform_subsidy", precision = 12, scale = 2)
    private BigDecimal platformSubsidy;

    @Column(name = "shop_discount", precision = 12, scale = 2)
    private BigDecimal shopDiscount;

    @Column(name = "promotion_breakdown", columnDefinition = "TEXT")
    private String promotionBreakdown;

    @Convert(converter = OrderStatusConverter.class)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "last_saga_status_sequence", nullable = false)
    private long lastSagaStatusSequence;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "delivery_lat")
    private Double deliveryLat;

    @Column(name = "delivery_lng")
    private Double deliveryLng;

    @Column(name = "pickup_lat")
    private Double pickupLat;

    @Column(name = "pickup_lng")
    private Double pickupLng;

    @Column(name = "restaurant_name")
    private String restaurantName;

    @Column(name = "restaurant_address")
    private String restaurantAddress;

    @Column(name = "restaurant_phone")
    private String restaurantPhone;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_by_principal_id")
    private Long cancelledByPrincipalId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    /** Auth-owned identity of the restaurant owner; creatorId remains profile compatibility data. */
    @Column(name = "creator_principal_id")
    private Long creatorPrincipalId;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode = SimulationContext.ExecutionMode.REAL.name();

    @Column(name = "simulation_run_id")
    private UUID simulationRunId;

    @Column(name = "simulation_cohort_id")
    private UUID simulationCohortId;

    @Column(name = "simulation_binding_version")
    private Long simulationBindingVersion;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (creatorId == null) {
            creatorId = userId;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public SimulationContext getSimulationContext() {
        if (!SimulationContext.ExecutionMode.SIMULATION.name().equals(executionMode)) {
            return SimulationContext.real();
        }
        return new SimulationContext(SimulationContext.ExecutionMode.SIMULATION, simulationRunId,
                simulationCohortId, simulationBindingVersion);
    }

    public void setSimulationContext(SimulationContext context) {
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        this.executionMode = normalized.mode().name();
        this.simulationRunId = normalized.runId();
        this.simulationCohortId = normalized.cohortId();
        this.simulationBindingVersion = normalized.bindingVersion();
    }
}
