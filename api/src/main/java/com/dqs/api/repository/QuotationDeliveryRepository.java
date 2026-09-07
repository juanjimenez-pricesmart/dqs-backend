package com.dqs.api.repository;

import com.dqs.api.model.QuotationDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationDeliveryRepository extends JpaRepository<QuotationDelivery, Long> {

    Optional<QuotationDelivery> findByQuotation_Id(Long quotationId);

    void deleteByQuotation_Id(Long quotationId);

    /**
     * Addresses this member has had a delivery sent to, most recent first.
     *
     * Only sold quotations count (status 3), as legacy does. Legacy's own query
     * (Model_orders::getLastDeliveryAddress) orders `fecha_entrega ASC LIMIT 1`
     * and so returns the OLDEST address despite its name — a member who moved
     * would be offered an address they left years ago, forever. That reads as a
     * typo rather than a decision, and this orders DESC. Confirmed with the PO.
     *
     * Callers pass PageRequest.of(0, 1) for the single most recent one.
     */
    @Query("""
           SELECT d.address
           FROM QuotationDelivery d
           WHERE d.quotation.customer.customerMembership = :membership
             AND d.quotation.statusId = 3
             AND d.address IS NOT NULL
             AND d.address <> ''
           ORDER BY d.deliveryDate DESC
           """)
    List<String> findAddressesByMembership(@Param("membership") String membership, Pageable pageable);
}
