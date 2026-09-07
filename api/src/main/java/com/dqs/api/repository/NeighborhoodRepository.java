package com.dqs.api.repository;

import com.dqs.api.model.Neighborhood;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NeighborhoodRepository extends JpaRepository<Neighborhood, Integer> {

    List<Neighborhood> findByZoneCodeAndCityCodeOrderByName(String zoneCode, String cityCode);

    Optional<Neighborhood> findByCodeAndZoneCodeAndCityCode(String code, String zoneCode, String cityCode);
}
