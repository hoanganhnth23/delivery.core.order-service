package com.delivery.order_service.service;

import com.delivery.order_service.entity.SagaCommandReceipt;
import com.delivery.order_service.repository.SagaCommandReceiptRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

class SagaCommandReceiptServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void firstCommandIsFlushedBeforeItsOrderMutation() {
        SagaCommandReceiptRepository repository = mock(SagaCommandReceiptRepository.class);
        when(repository.findById(EVENT_ID)).thenReturn(Optional.empty());
        when(repository.insertIfAbsentPostgres(any(), anyString(), anyLong(), anyString(), anyString())).thenReturn(1);
        SagaCommandReceiptService service = new SagaCommandReceiptService(repository);

        assertThat(service.claim(EVENT_ID, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                "FINDING_SHIPPER", "{\"eventId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}"))
                .isTrue();

        verify(repository).insertIfAbsentPostgres(eq(EVENT_ID), eq(SagaCommandReceiptService.UPDATE_ORDER_STATUS),
                eq(101L), eq("FINDING_SHIPPER"), argThat(fingerprint -> fingerprint.length() == 64));
    }

    @Test
    void exactReplayIsAnAcknowledgedNoOp() {
        String payload = "{\"eventId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}";
        SagaCommandReceiptRepository repository = mock(SagaCommandReceiptRepository.class);
        SagaCommandReceipt receipt = new SagaCommandReceipt(
                EVENT_ID, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                "FINDING_SHIPPER", sha256(payload));
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(receipt));
        SagaCommandReceiptService service = new SagaCommandReceiptService(repository);

        assertThat(service.claim(EVENT_ID, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                "FINDING_SHIPPER", payload)).isFalse();
    }

    @Test
    void sameIdentityWithDifferentPayloadFailsClosed() {
        SagaCommandReceiptRepository repository = mock(SagaCommandReceiptRepository.class);
        SagaCommandReceipt receipt = new SagaCommandReceipt(
                EVENT_ID, SagaCommandReceiptService.UPDATE_ORDER_STATUS, 101L,
                "FINDING_SHIPPER", sha256("{\"candidate\":1}"));
        when(repository.findById(EVENT_ID)).thenReturn(Optional.of(receipt));
        SagaCommandReceiptService service = new SagaCommandReceiptService(repository);

        assertThatThrownBy(() -> service.claim(EVENT_ID, SagaCommandReceiptService.UPDATE_ORDER_STATUS,
                101L, "FINDING_SHIPPER", "{\"candidate\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contradictory");
    }

    private String sha256(String payload) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
