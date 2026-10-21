package com.delivery.order_service.service;

import com.delivery.order_service.repository.CheckoutQuoteRepository;
import com.delivery.order_service.repository.OrderCreateIdempotencyReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** Retains short-lived checkout command evidence long enough for safe retries. */
@Component
@Slf4j
public class CheckoutQuoteCleanupJob {
    private final CheckoutQuoteRepository quoteRepository;
    private final OrderCreateIdempotencyReceiptRepository receiptRepository;
    private final Clock clock;
    private final Duration retention;

    public CheckoutQuoteCleanupJob(CheckoutQuoteRepository quoteRepository,
                                   OrderCreateIdempotencyReceiptRepository receiptRepository,
                                   Clock clock,
                                   @Value("${app.order.quote.cleanup-retention:PT24H}") Duration retention) {
        this.quoteRepository = quoteRepository;
        this.receiptRepository = receiptRepository;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.order.quote.cleanup-delay-ms:3600000}")
    @Transactional
    public void cleanup() {
        var cutoff = clock.instant().minus(retention);
        long quotes = quoteRepository.deleteByExpiresAtBefore(cutoff);
        long receipts = receiptRepository.deleteByCreatedAtBefore(cutoff);
        if (quotes > 0 || receipts > 0) {
            log.info("Deleted {} expired checkout quote(s) and {} idempotency receipt(s)", quotes, receipts);
        }
    }
}
