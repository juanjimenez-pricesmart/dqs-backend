package com.dqs.api.repository;

import com.dqs.api.model.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityRepository extends JpaRepository<City, Integer> {

    List<City> findByCountryOrderByName(String country);

    Optional<City> findByCodeAndCountry(String code, String country);
}
