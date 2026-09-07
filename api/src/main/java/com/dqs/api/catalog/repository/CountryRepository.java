package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CountryRepository extends JpaRepository<Country, Integer> {

    Optional<Country> findByCode(String iso2);

    List<Country> findByActiveTrueOrderByName();
}
