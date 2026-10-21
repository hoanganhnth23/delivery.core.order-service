package com.delivery.order_service.service;

import com.delivery.order_service.dto.request.CheckoutPreviewRequest;
import com.delivery.order_service.dto.response.CheckoutPreviewResponse;
import com.delivery.order_service.entity.CheckoutQuote;
import com.delivery.order_service.repository.CheckoutQuoteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutQuoteIssuerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T01:00:00Z");

    private final CheckoutPreviewService previews = mock(CheckoutPreviewService.class);
    private final CheckoutFingerprintService fingerprints = mock(CheckoutFingerprintService.class);
    private final CheckoutQuoteRepository repository = mock(CheckoutQuoteRepository.class);
    private final CheckoutQuoteIssuer issuer = new CheckoutQuoteIssuer(previews, fingerprints, repository,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void issuePersistsTheRemotePreviewAsAQuoteBoundToThePrincipal() {
        CheckoutPreviewRequest request = new CheckoutPreviewRequest();
        CheckoutPreviewResponse preview = CheckoutPreviewResponse.builder().build();
        when(previews.calculatePreview(request, 100L, 10L)).thenReturn(preview);
        when(fingerprints.pricingInput(request)).thenReturn("input-fp");
        when(fingerprints.pricingSnapshot(preview)).thenReturn("pricing-fp");

        CheckoutPreviewResponse issued = issuer.issue(request, 100L, 10L);

        ArgumentCaptor<CheckoutQuote> saved = ArgumentCaptor.forClass(CheckoutQuote.class);
        verify(repository).save(saved.capture());
        CheckoutQuote quote = saved.getValue();
        assertThat(issued).isSameAs(preview);
        assertThat(issued.getQuoteId()).isNotNull().isEqualTo(quote.getQuoteId());
        assertThat(issued.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(quote.getPrincipalId()).isEqualTo(100L);
        assertThat(quote.getPricingInputFingerprint()).isEqualTo("input-fp");
        assertThat(quote.getPricingFingerprint()).isEqualTo("pricing-fp");
        assertThat(quote.getExpiresAt()).isEqualTo(issued.getExpiresAt());
        assertThat(quote.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistRunsInsideTheTransactionTemplateWhenAvailable() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<CheckoutPreviewResponse>) invocation.getArgument(0)).doInTransaction(null));
        ReflectionTestUtils.setField(issuer, "transactionTemplate", template);
        CheckoutPreviewResponse preview = CheckoutPreviewResponse.builder().build();

        CheckoutPreviewResponse persisted = issuer.persist(new CheckoutPreviewRequest(), preview, 7L);

        assertThat(persisted.getQuoteId()).isNotNull();
        verify(template).execute(any());
        verify(repository).save(any(CheckoutQuote.class));
    }

    @Test
    void persistFailsLoudlyWhenTheTransactionReturnsNothing() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenReturn(null);
        ReflectionTestUtils.setField(issuer, "transactionTemplate", template);

        assertThatThrownBy(() -> issuer.persist(new CheckoutPreviewRequest(),
                CheckoutPreviewResponse.builder().build(), 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Quote persistence transaction returned no response");
    }
}
