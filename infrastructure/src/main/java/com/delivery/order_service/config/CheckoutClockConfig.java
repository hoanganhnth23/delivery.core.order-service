package com.delivery.order_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CheckoutClockConfig {
    @Bean
    Clock checkoutClock() {
        return Clock.systemUTC();
    }
}
