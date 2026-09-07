package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    /**
     * The rate in force for a country: the most recent one, which is what every
     * caller of the old ps_tasa_cambio query actually wanted.
     */
    Optional<ExchangeRate> findFirstByCountry_CodeOrderByEffectiveDateDesc(String iso2);

    Optional<ExchangeRate> findByCountry_CodeAndEffectiveDate(String iso2, LocalDate effectiveDate);
}
