package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.RouteType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RouteTypeRepository extends JpaRepository<RouteType, Integer> {

    Optional<RouteType> findByCode(String code);
}
