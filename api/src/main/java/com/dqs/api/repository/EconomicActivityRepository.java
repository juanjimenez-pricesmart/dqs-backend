package com.dqs.api.repository;

import com.dqs.api.model.EconomicActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EconomicActivityRepository extends JpaRepository<EconomicActivity, Integer> {

    List<EconomicActivity> findByCountryOrderByValue(String country);

    /** `code` carries its own unique index, so it identifies a row on its own. */
    Optional<EconomicActivity> findByCode(String code);
}
