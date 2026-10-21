package com.delivery.order_service.repository;

import com.delivery.order_service.service.CheckoutFingerprintService;
import com.delivery.order_service.service.OrderCreateIdempotencyService;
import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import com.delivery.order_service.exception.OrderApiException;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the production PostgreSQL ON CONFLICT fence under concurrent claims. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "eureka.client.enabled=false"
})
@Import(OrderCreateIdempotencyService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderCreateIdempotencyPostgresConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_idempotency")
            .withUsername("order")
            .withPassword("order");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    @Autowired private OrderCreateIdempotencyReceiptRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderCreateIdempotencyService idempotency;

    @Test
    void concurrentSamePrincipalAndKeyCreateExactlyOneReceipt() throws Exception {
        UUID key = UUID.randomUUID();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> claim(startTogether, key));
            Future<Integer> second = executor.submit(() -> claim(startTogether, key));

            List<Integer> results = List.of(first.get(), second.get());
            assertThat(results).containsExactlyInAnyOrder(1, 0);
            assertThat(repository.findByPrincipalIdAndIdempotencyKey(77L, key)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApplicationAcquireHasOneLiveOwner() throws Exception {
        UUID key = UUID.randomUUID();
        assertSingleApplicationOwner(key);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredLeaseRaceReclaimsOnceAndFencesOldOwnerAndRelease() throws Exception {
        UUID key = UUID.randomUUID();
        UUID oldToken = UUID.randomUUID();
        new TransactionTemplate(transactionManager).execute(status ->
                repository.insertIfAbsentWithLeasePostgres(77L, key, "fingerprint",
                        CheckoutFingerprintService.VERSION, oldToken, Instant.now().minusSeconds(60)));
        OrderCreateIdempotencyReceipt winner = assertSingleApplicationOwner(key);
        assertThat(winner.getProcessingToken()).isNotEqualTo(oldToken);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> idempotency.claim(77L, key, "fingerprint", oldToken)))
                .isInstanceOfSatisfying(OrderApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
        idempotency.release(winner.getId(), oldToken);
        assertThat(repository.findByPrincipalIdAndIdempotencyKey(77L, key).orElseThrow().getProcessingToken())
                .isEqualTo(winner.getProcessingToken());
        transaction.execute(status -> {
            assertThat(idempotency.claim(77L, key, "fingerprint", winner.getProcessingToken()).getId())
                    .isEqualTo(winner.getId());
            return null;
        });
        idempotency.release(winner.getId(), winner.getProcessingToken());
        UUID nextToken = UUID.randomUUID();
        OrderCreateIdempotencyReceipt recovered = idempotency.acquire(77L, key, "fingerprint", nextToken);
        assertThat(recovered.getId()).isEqualTo(winner.getId());
        assertThat(recovered.getProcessingToken()).isEqualTo(nextToken);
    }

    private OrderCreateIdempotencyReceipt assertSingleApplicationOwner(UUID key) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> acquire(startTogether, key));
            Future<Object> second = executor.submit(() -> acquire(startTogether, key));
            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream().filter(OrderCreateIdempotencyReceipt.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(OrderApiException.class::isInstance).count()).isEqualTo(1);
            OrderApiException conflict = (OrderApiException) outcomes.stream()
                    .filter(OrderApiException.class::isInstance).findFirst().orElseThrow();
            assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            OrderCreateIdempotencyReceipt winner = (OrderCreateIdempotencyReceipt) outcomes.stream()
                    .filter(OrderCreateIdempotencyReceipt.class::isInstance).findFirst().orElseThrow();
            assertThat(repository.findByPrincipalIdAndIdempotencyKey(77L, key).orElseThrow().getProcessingToken())
                    .isEqualTo(winner.getProcessingToken());
            return winner;
        } finally {
            executor.shutdownNow();
        }
    }

    private Object acquire(CyclicBarrier barrier, UUID key) throws Exception {
        barrier.await();
        try { return idempotency.acquire(77L, key, "fingerprint", UUID.randomUUID()); }
        catch (OrderApiException conflict) { return conflict; }
    }

    private Integer claim(CyclicBarrier startTogether, UUID key) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            try {
                startTogether.await();
                return repository.insertIfAbsentPostgres(77L, key, "fingerprint",
                        CheckoutFingerprintService.VERSION);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }
}
