package com.dqs.api.repository;

import com.dqs.api.model.QuotationPaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuotationPaymentAttemptRepository extends JpaRepository<QuotationPaymentAttempt, Long> {

    Optional<QuotationPaymentAttempt> findByInvoiceId(String invoiceId);

    /** Attempts made so far; the next attempt number is this plus one. */
    long countByQuotation_Id(Long quotationId);
}
