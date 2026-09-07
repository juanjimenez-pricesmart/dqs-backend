package com.dqs.api.repository;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationCancelReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closing a quotation without a sale, and the catalog of reasons for it.
 *
 * Both tables are ours, so both go through JPA. The maps this returns keep the
 * `id` / `description` keys the frontend already reads.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class QuotationCancelRepository {

    /** Quotation status for a quote closed without a sale. */
    private static final int STATUS_CANCELLED = 4;

    private final QuotationCancelReasonRepository reasonRepository;
    private final QuotationRepository quotationRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReasons() {
        return reasonRepository.findAllByOrderByIdAsc().stream()
            .map(QuotationCancelRepository::toMap)
            .toList();
    }

    @Transactional
    public void cancel(Long quotationId, int reasonId) {
        Quotation quotation = quotationRepository.findById(quotationId)
            .orElseThrow(() -> new IllegalArgumentException("Quotation not found: " + quotationId));
        quotation.setStatusId(STATUS_CANCELLED);
        quotation.setCancelReasonId(reasonId);
        quotationRepository.save(quotation);
    }

    private static Map<String, Object> toMap(QuotationCancelReason reason) {
        Map<String, Object> m = new LinkedHashMap<>(2);
        m.put("id", reason.getId());
        m.put("description", reason.getDescription());
        return m;
    }
}
