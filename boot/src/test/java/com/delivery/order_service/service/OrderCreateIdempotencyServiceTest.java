package com.delivery.order_service.service;

import com.delivery.order_service.entity.OrderCreateIdempotencyReceipt;
import com.delivery.order_service.exception.OrderApiException;
import com.delivery.order_service.repository.OrderCreateIdempotencyReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreateIdempotencyServiceTest {

    @Mock private OrderCreateIdempotencyReceiptRepository repository;

    private OrderCreateIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new OrderCreateIdempotencyService(repository);
        ReflectionTestUtils.setField(service, "dataSourceUrl", "jdbc:h2:mem:checkout");
    }

    @Test
    void firstClaimPersistsTheVersionedFingerprintAndReturnsReceipt() {
        UUID key = UUID.randomUUID();
        OrderCreateIdempotencyReceipt receipt = new OrderCreateIdempotencyReceipt(
                77L, key, "fingerprint", CheckoutFingerprintService.VERSION, Instant.now());
        when(repository.findByPrincipalIdAndIdempotencyKey(77L, key))
                .thenReturn(Optional.empty(), Optional.of(receipt));
        when(repository.insertIfAbsentH2(77L, key, "fingerprint", CheckoutFingerprintService.VERSION))
                .thenReturn(1);

        OrderCreateIdempotencyReceipt claimed = service.claim(77L, key, "fingerprint");

        assertThat(claimed).isSameAs(receipt);
        verify(repository).insertIfAbsentH2(77L, key, "fingerprint", CheckoutFingerprintService.VERSION);
    }

    @Test
    void sameKeyWithSameFingerprintReturnsTheOriginalCompletedReceipt() {
        UUID key = UUID.randomUUID();
        OrderCreateIdempotencyReceipt receipt = new OrderCreateIdempotencyReceipt(
                77L, key, "fingerprint", CheckoutFingerprintService.VERSION, Instant.now());
        receipt.complete(1234L);
        when(repository.findByPrincipalIdAndIdempotencyKey(77L, key)).thenReturn(Optional.of(receipt));

        OrderCreateIdempotencyReceipt claimed = service.claim(77L, key, "fingerprint");

        assertThat(claimed.getOrderId()).isEqualTo(1234L);
    }

    @Test
    void sameKeyWithDifferentFingerprintReturnsTypedConflict() {
        UUID key = UUID.randomUUID();
        OrderCreateIdempotencyReceipt receipt = new OrderCreateIdempotencyReceipt(
                77L, key, "original", CheckoutFingerprintService.VERSION, Instant.now());
        when(repository.findByPrincipalIdAndIdempotencyKey(77L, key)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> service.claim(77L, key, "different"))
                .isInstanceOfSatisfying(OrderApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void existingIncompleteReceiptFailsClosedInsteadOfCreatingAnotherOrder() {
        UUID key = UUID.randomUUID();
        OrderCreateIdempotencyReceipt receipt = new OrderCreateIdempotencyReceipt(
                77L, key, "fingerprint", CheckoutFingerprintService.VERSION, Instant.now());
        when(repository.findByPrincipalIdAndIdempotencyKey(77L, key)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> service.claim(77L, key, "fingerprint"))
                .isInstanceOfSatisfying(OrderApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_IN_PROGRESS"));
    }
}
