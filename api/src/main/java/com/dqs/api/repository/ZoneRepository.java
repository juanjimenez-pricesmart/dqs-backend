package com.dqs.api.repository;

import com.dqs.api.model.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, Integer> {

    List<Zone> findByCityCodeOrderByName(String cityCode);

    Optional<Zone> findByCodeAndCityCode(String code, String cityCode);
}
