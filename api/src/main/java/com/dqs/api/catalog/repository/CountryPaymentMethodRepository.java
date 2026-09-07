package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.CountryPaymentMethod;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CountryPaymentMethodRepository extends JpaRepository<CountryPaymentMethod, Integer> {

    /** What the payment dropdown offers in a country. Replaces the orders_pago read. */
    @EntityGraph(attributePaths = "methodType")
    List<CountryPaymentMethod> findByCountry_Iso2AndActiveTrueOrderBySortOrder(String iso2);
}
