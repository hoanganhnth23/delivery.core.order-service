package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderOutboxLeaseServiceTest {
    final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    final OrderOutboxLeaseService service = new OrderOutboxLeaseService(repository);
    final UUID token = UUID.randomUUID();

    OutboxEvent owned() {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L); event.setStatus(OutboxEvent.Status.IN_FLIGHT);
        event.setLeaseToken(token); event.setLeaseUntil(LocalDateTime.now().plusSeconds(30));
        event.setAttempts(0);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        return event;
    }

    @Test void claimBoundsBatchAndLeaseAndPersistsOwnership() {
        OutboxEvent event = new OutboxEvent();
        when(repository.lockNextClaimableBatch(500)).thenReturn(List.of(event));
        LocalDateTime before = LocalDateTime.now();
        assertThat(service.claim(1000, token, 0)).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.IN_FLIGHT);
        assertThat(event.getLeaseToken()).isEqualTo(token);
        assertThat(event.getLeaseUntil()).isAfterOrEqualTo(before.plusSeconds(5));
        verify(repository).save(event);
    }

    @Test void emptyClaimNeverWrites() {
        when(repository.lockNextClaimableBatch(1)).thenReturn(null, List.of());
        assertThat(service.claim(0, token, 30)).isEmpty();
        assertThat(service.claim(-1, token, 30)).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test void sentClearsLeaseAndPreviousError() {
        OutboxEvent event = owned(); event.setLastError("old failure");
        assertThat(service.markSent(1L, token)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getLeaseToken()).isNull(); assertThat(event.getLeaseUntil()).isNull();
        assertThat(event.getLastError()).isNull(); verify(repository).save(event);
    }

    @Test void retryBackoffAndDeadLetterClearLease() {
        OutboxEvent event = owned();
        LocalDateTime before = LocalDateTime.now();
        assertThat(service.markFailure(1L, token, new IllegalStateException("broker down"), 2)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(1));
        assertThat(event.getLastError()).contains("broker down");
        assertThat(event.getLeaseToken()).isNull(); assertThat(event.getLeaseUntil()).isNull();
        event.setStatus(OutboxEvent.Status.IN_FLIGHT); event.setLeaseToken(token);
        assertThat(service.markFailure(1L, token, new IllegalStateException("x".repeat(2500)), 2)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.DEAD);
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getLastError()).hasSizeLessThanOrEqualTo(2000);
        verify(repository, times(2)).save(event);
    }

    @Test void staleOrMissingOwnershipCannotAcknowledgeOrFail() {
        assertThat(service.markSent(null, token)).isFalse();
        assertThat(service.markFailure(1L, null, new RuntimeException(), 2)).isFalse();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.markSent(1L, token)).isFalse();
        OutboxEvent event = owned();
        assertThat(service.markSent(1L, UUID.randomUUID())).isFalse();
        event.setStatus(OutboxEvent.Status.SENT);
        assertThat(service.markFailure(1L, token, new RuntimeException(), 2)).isFalse();
        verify(repository, never()).save(any());
    }
}
