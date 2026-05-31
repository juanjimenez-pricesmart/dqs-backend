// repository/QuotationItemRepository.java
package com.dqs.api.repository;

import com.dqs.api.model.QuotationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationItemRepository extends JpaRepository<QuotationItem, Long> {

    List<QuotationItem> findByQuotationIdOrderByProductIdAsc(Long quotationId);

    Optional<QuotationItem> findByQuotationIdAndProductId(Long quotationId, String productId);

    boolean existsByQuotationIdAndProductId(Long quotationId, String productId);
}