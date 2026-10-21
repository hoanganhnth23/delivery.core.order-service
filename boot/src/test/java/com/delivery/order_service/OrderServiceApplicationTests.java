package com.delivery.order_service;

import com.delivery.order_service.listener.PaymentEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		assertThat(context.getBeansOfType(PaymentEventListener.class)).isEmpty();
		assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).hasSize(1);
	}

}
