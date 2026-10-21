package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.entity.CheckoutQuote;
import com.delivery.order_service.repository.CheckoutQuoteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Persists a price confirmation independently from a rejected create command. */
@Service
public class CheckoutQuoteIssuer {
    private final CheckoutPreviewService previewService;
    private final CheckoutFingerprintService fingerprints;
    private final CheckoutQuoteRepository repository;
    private final Clock clock;
    private final Duration ttl;
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;

    public CheckoutQuoteIssuer(CheckoutPreviewService previewService,
                               CheckoutFingerprintService fingerprints,
                               CheckoutQuoteRepository repository,
                               Clock clock,
                               @Value("${app.order.quote.ttl:PT5M}") Duration ttl) {
        this.previewService = previewService;
        this.fingerprints = fingerprints;
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Keep the remote preview outside the quote persistence transaction. A
     * failed/slow restaurant dependency must not pin a quote DB connection;
     * {@link #persist} performs the short local write afterwards.
     */
    public CheckoutPreviewResponse issue(CheckoutPreviewRequest request, Long principalId, Long userId) {
        return com.delivery.order.application.QuoteWorkflow.issue(
                new com.delivery.order.application.api.QuoteIssuePorts<CheckoutPreviewResponse>() {
                    public CheckoutPreviewResponse preview() {
                        return previewService.calculatePreview(request, principalId, userId);
                    }
                    public CheckoutPreviewResponse persist(CheckoutPreviewResponse response) {
                        return CheckoutQuoteIssuer.this.persist(request, response, principalId);
                    }
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CheckoutPreviewResponse persist(CheckoutPreviewRequest request, CheckoutPreviewResponse response,
                                           Long principalId) {
        if (transactionTemplate == null) {
            return persistLocal(request, response, principalId);
        }
        CheckoutPreviewResponse persisted = transactionTemplate.execute(status ->
                persistLocal(request, response, principalId));
        if (persisted == null) {
            throw new IllegalStateException("Quote persistence transaction returned no response");
        }
        return persisted;
    }

    private CheckoutPreviewResponse persistLocal(CheckoutPreviewRequest request,
                                                  CheckoutPreviewResponse response,
                                                  Long principalId) {
        Instant now = clock.instant();
        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = com.delivery.order.domain.CheckoutQuotePolicy.expiresAt(now, ttl);
        repository.save(new CheckoutQuote(quoteId, principalId,
                fingerprints.pricingInput(request), fingerprints.pricingSnapshot(response), expiresAt, now));
        response.setQuoteId(quoteId);
        response.setExpiresAt(expiresAt);
        return response;
    }
}
