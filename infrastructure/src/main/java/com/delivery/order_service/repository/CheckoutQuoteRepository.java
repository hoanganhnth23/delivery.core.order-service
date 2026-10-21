package com.delivery.order_service.repository;

import com.delivery.order_service.entity.CheckoutQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutQuoteRepository extends JpaRepository<CheckoutQuote, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from CheckoutQuote q where q.quoteId = :quoteId")
    Optional<CheckoutQuote> findByIdForUpdate(@Param("quoteId") UUID quoteId);

    long deleteByExpiresAtBefore(Instant cutoff);
}
