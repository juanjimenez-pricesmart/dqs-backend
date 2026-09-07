package com.dqs.api.repository;

import com.dqs.api.model.QuotationCancelReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuotationCancelReasonRepository extends JpaRepository<QuotationCancelReason, Integer> {

    List<QuotationCancelReason> findAllByOrderByIdAsc();
}
