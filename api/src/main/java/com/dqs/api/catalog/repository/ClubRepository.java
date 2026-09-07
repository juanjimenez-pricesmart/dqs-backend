package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.Club;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClubRepository extends JpaRepository<Club, Integer> {

    /**
     * By the operational code — 6101, 6410 — which is what every caller has.
     *
     * The country comes along: almost everything that looks a club up then wants
     * its currency, language or tax settings, and the association is lazy.
     */
    @EntityGraph(attributePaths = "country")
    Optional<Club> findByClubNumber(Integer clubNumber);

    @EntityGraph(attributePaths = "country")
    List<Club> findByActiveTrueOrderByName();

    List<Club> findByCountry_Iso2AndActiveTrueOrderByName(String iso2);
}
