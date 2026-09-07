package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.Route;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, Integer> {

    /**
     * Active routes of a club with their tariffs and type, in one query.
     *
     * This replaces the hand-written join against ps_rutas that the delivery
     * panel used. The entity graph matters: the panel shows every tariff tier
     * beside every route, so lazy loading would issue a query per route.
     */
    @EntityGraph(attributePaths = {"prices", "routeType"})
    List<Route> findByClub_ClubNumberAndActiveTrueOrderByCode(Integer clubNumber);

    @EntityGraph(attributePaths = {"prices", "routeType"})
    Optional<Route> findByClub_ClubNumberAndCode(Integer clubNumber, String code);
}
