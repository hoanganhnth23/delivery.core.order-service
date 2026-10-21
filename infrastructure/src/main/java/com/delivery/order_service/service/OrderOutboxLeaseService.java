package com.delivery.order_service.service;

import com.delivery.order_service.entity.OutboxEvent;
import com.delivery.order_service.repository.OutboxEventRepository;
import com.delivery.observability.SafeLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Owns only short database transactions around outbox claim/update operations.
 * Kafka I/O never runs through this service.
 */
@Service
public class OrderOutboxLeaseService {
    private final OutboxEventRepository repository;

    public OrderOutboxLeaseService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<OutboxEvent> claim(int batchSize, UUID leaseToken, long leaseSeconds) {
        if (leaseToken == null) {
            throw new IllegalArgumentException("Outbox lease token is required");
        }
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(Math.max(5, leaseSeconds));
        List<OutboxEvent> events = repository.lockNextClaimableBatch(Math.max(1, Math.min(batchSize, 500)));
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        for (OutboxEvent event : events) {
            event.setStatus(OutboxEvent.Status.IN_FLIGHT);
            event.setLeaseToken(leaseToken);
            event.setLeaseUntil(leaseUntil);
            repository.save(event);
        }
        return events;
    }

    @Transactional
    public boolean markSent(Long eventId, UUID leaseToken) {
        OutboxEvent event = owned(eventId, leaseToken);
        if (event == null) return false;
        event.setStatus(OutboxEvent.Status.SENT);
        event.setSentAt(LocalDateTime.now());
        event.setLastError(null);
        event.setLeaseToken(null);
        event.setLeaseUntil(null);
        repository.save(event);
        return true;
    }

    @Transactional
    public boolean markFailure(Long eventId, UUID leaseToken, Throwable failure, int maxAttempts) {
        OutboxEvent event = owned(eventId, leaseToken);
        if (event == null) return false;

        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(abbreviate(SafeLog.exceptionMessage(failure)));
        if (attempts >= Math.max(1, Math.min(maxAttempts, 100))) {
            event.setStatus(OutboxEvent.Status.DEAD);
        } else {
            long delaySeconds = Math.min(300L, 1L << Math.min(Math.max(attempts - 1, 0), 8));
            event.setStatus(OutboxEvent.Status.PENDING);
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
        }
        event.setLeaseToken(null);
        event.setLeaseUntil(null);
        repository.save(event);
        return true;
    }

    private OutboxEvent owned(Long eventId, UUID leaseToken) {
        if (eventId == null || leaseToken == null) return null;
        OutboxEvent event = repository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() != OutboxEvent.Status.IN_FLIGHT
                || !leaseToken.equals(event.getLeaseToken())) {
            return null;
        }
        return event;
    }

    private String abbreviate(String message) {
        if (message == null) return "Unknown Kafka publish failure";
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
