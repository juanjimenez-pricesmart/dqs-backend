package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.FiscalDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FiscalDocumentTypeRepository extends JpaRepository<FiscalDocumentType, Integer> {

    /** Ordered by the Spanish label, as the legacy dropdown was. */
    List<FiscalDocumentType> findByCountry_CodeAndActiveTrueOrderByNameEs(String iso2);

    Optional<FiscalDocumentType> findByCountry_CodeAndCode(String iso2, String code);
}
