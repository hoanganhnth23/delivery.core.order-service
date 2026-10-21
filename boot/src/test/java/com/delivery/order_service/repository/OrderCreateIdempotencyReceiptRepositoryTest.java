package com.delivery.order_service.repository;

import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import com.delivery.order_service.service.CheckoutFingerprintService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@ActiveProfiles("test")
class OrderCreateIdempotencyReceiptRepositoryTest {

    @Autowired private OrderCreateIdempotencyReceiptRepository repository;

    @Test
    void h2CompatibilityClaimInsertsOnlyOneScopedKey() {
        UUID key = UUID.randomUUID();

        assertThat(repository.insertIfAbsentH2(77L, key, "fingerprint",
                CheckoutFingerprintService.VERSION)).isEqualTo(1);
        assertThat(repository.insertIfAbsentH2(77L, key, "fingerprint",
                CheckoutFingerprintService.VERSION)).isZero();

        OrderCreateIdempotencyReceipt receipt = repository
                .findByPrincipalIdAndIdempotencyKey(77L, key)
                .orElseThrow();
        assertThat(receipt.getRequestFingerprint()).isEqualTo("fingerprint");
        assertThat(receipt.getOrderId()).isNull();
    }
}
