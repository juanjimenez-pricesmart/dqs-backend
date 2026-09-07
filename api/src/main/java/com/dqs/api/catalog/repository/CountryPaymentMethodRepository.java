package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.CountryPaymentMethod;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CountryPaymentMethodRepository extends JpaRepository<CountryPaymentMethod, Integer> {

    /**
     * What the payment dropdown offers in a country. Replaces the orders_pago read.
     *
     * Ordered by sort_order first, then by name. Every imported row has
     * sort_order 0, so today the name decides and the list comes out in the same
     * alphabetical order legacy produced; setting a non-zero value on a row
     * moves it without touching this query.
     */
    @EntityGraph(attributePaths = "methodType")
    List<CountryPaymentMethod> findByCountry_CodeAndActiveTrueOrderBySortOrderAscMethodType_NameAsc(String iso2);
}
