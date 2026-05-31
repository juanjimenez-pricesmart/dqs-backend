// repository/QuotationRepository.java
package com.dqs.api.repository;

import com.dqs.api.model.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long> {
}