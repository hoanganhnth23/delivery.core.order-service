package com.delivery.order_service.service;

import com.delivery.order_service.OrderServiceApplication;
import com.delivery.order_service.dto.event.DeliveryStatusUpdatedEvent;
import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderStatus;
import com.delivery.order_service.repository.OrderRepository;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves two Order Kafka consumers converge through atomic Saga receipt claim. */
@SpringBootTest(classes = OrderServiceApplication.class, properties = {
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.relay-enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SagaOrderCommandPostgresConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_concurrency")
            .withUsername("order")
            .withPassword("order");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    @Autowired private SagaOrderCommandProcessor processor;
    @Autowired private OrderRepository orderRepository;
    @Autowired private SagaCommandReceiptRepository receiptRepository;

    @BeforeEach
    void clean() {
        receiptRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void concurrentSameSagaCommandCreatesOneReceiptAndOneOrderTransition() throws Exception {
        Order order = new Order();
        order.setUserId(7L);
        order.setCreatorId(7L);
        order.setRestaurantId(8L);
        order.setStatus(OrderStatus.PENDING);
        order.setPaymentMethod("COD");
        order.setSubtotalPrice(new BigDecimal("100000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingFee(new BigDecimal("15000"));
        order.setTotalPrice(new BigDecimal("115000"));
        order = orderRepository.saveAndFlush(order);
        Long orderId = order.getId();

        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"orderId\":" + orderId
                + ",\"sagaStatus\":\"FINDING_SHIPPER\"}";
        DeliveryStatusUpdatedEvent event = new DeliveryStatusUpdatedEvent();
        event.setOrderId(orderId);
        event.setStatus("FINDING_SHIPPER");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> invokeTogether(ready, start,
                    () -> processor.applyDeliveryStatus(eventId, orderId, "FINDING_SHIPPER",
                            payload, event)));
            Future<Throwable> second = executor.submit(() -> invokeTogether(ready, start,
                    () -> processor.applyDeliveryStatus(eventId, orderId, "FINDING_SHIPPER",
                            payload, event)));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(receiptRepository.count()).isEqualTo(1);
        assertThat(orderRepository.findById(order.getId())).get()
                .extracting(Order::getStatus)
                .isEqualTo(OrderStatus.FINDING_SHIPPER);
    }

    private Throwable invokeTogether(CountDownLatch ready, CountDownLatch start,
                                     ThrowingOperation operation) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent Order test did not start");
            }
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
