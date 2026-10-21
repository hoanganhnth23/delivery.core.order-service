package com.delivery.order_service.common.constants;

/**
 * ✅ Kafka Topic Constants cho Order Service theo AI Coding Instructions
 */
public class KafkaTopicConstants {
    
    // Payment listeners are conditional and remain disabled in COD-only MVP.
    public static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    public static final String PAYMENT_FAILED_TOPIC = "payment.failed";
    
    private KafkaTopicConstants() {
        // Utility class - prevent instantiation
    }
}
