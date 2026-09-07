package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.PaymentMethodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentMethodTypeRepository extends JpaRepository<PaymentMethodType, Integer> {

    Optional<PaymentMethodType> findByCode(String code);
}
