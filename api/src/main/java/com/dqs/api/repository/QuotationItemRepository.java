package com.dqs.api.repository;

import com.dqs.api.model.QuotationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationItemRepository extends JpaRepository<QuotationItem, Long> {

    List<QuotationItem> findByQuotation_IdOrderByProductIdAsc(Long quotationId);

    Optional<QuotationItem> findByQuotation_IdAndProductId(Long quotationId, String productId);

    /**
     * Every line for a product, not just the first.
     *
     * Nothing enforces one line per product at the database level, and the
     * delivery cleanup has to remove all of them or it leaves a 888905 row
     * behind with no delivery record backing it.
     */
    List<QuotationItem> findAllByQuotation_IdAndProductId(Long quotationId, String productId);
}
