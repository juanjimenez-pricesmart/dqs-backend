package com.dqs.api.catalog.repository;

import com.dqs.api.catalog.model.DeliveryCity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryCityRepository extends JpaRepository<DeliveryCity, Integer> {

    List<DeliveryCity> findByCountry_CodeAndActiveTrueOrderByName(String countryIso2);
}
