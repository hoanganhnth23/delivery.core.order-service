package com.delivery.order_service.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ✅ Payment Event DTO theo AI Coding Instructions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {
    
    private Long paymentId;
    private Long orderId;
    private Long userId;
    private String status; // COMPLETED, FAILED, PENDING, REFUNDED
    private Double amount;
    private String paymentMethod; // CASH, CARD, WALLET
    private String transactionId;
    private LocalDateTime processedAt;
    private String failureReason; // For failed payments
}
