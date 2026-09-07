package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.RoutePrice;
import com.dqs.api.catalog.model.RouteUnitType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoutePriceRepository extends JpaRepository<RoutePrice, Integer> {

    List<RoutePrice> findByRoute_Id(Integer routeId);

    Optional<RoutePrice> findByRoute_IdAndUnitType(Integer routeId, RouteUnitType unitType);
}
