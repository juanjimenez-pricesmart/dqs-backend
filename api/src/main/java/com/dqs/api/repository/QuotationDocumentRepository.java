package com.dqs.api.repository;

import com.dqs.api.model.QuotationDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuotationDocumentRepository extends JpaRepository<QuotationDocument, Long> {

    List<QuotationDocument> findByQuotation_IdOrderByIdAsc(Long quotationId);

    List<QuotationDocument> findByQuotation_IdAndDocumentType_CodeOrderByIdAsc(
            Long quotationId, String documentTypeCode);

    long countByQuotation_IdAndDocumentType_Code(Long quotationId, String documentTypeCode);
}
