package com.dqs.api.repository;

import com.dqs.api.model.QuotationFiscal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuotationFiscalRepository extends JpaRepository<QuotationFiscal, Long> {

    Optional<QuotationFiscal> findByQuotation_Id(Long quotationId);
}
