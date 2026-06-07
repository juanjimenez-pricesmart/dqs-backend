package com.dqs.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/diag")
@RequiredArgsConstructor
public class DiagController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/tables")
    public List<String> tables() {
        return jdbcTemplate.queryForList(
            "SHOW TABLES LIKE 'ps_%'", String.class);
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        return jdbcTemplate.queryForList(
            "SELECT ps_tienda_id, nombre, pais, pais_iso2, moneda, status FROM ps_tienda ORDER BY pais, nombre");
    }

    @GetMapping("/store/{id}")
    public List<Map<String, Object>> store(@PathVariable int id) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM ps_tienda WHERE ps_tienda_id = ?", id);
    }

    @GetMapping("/user/{id}")
    public List<Map<String, Object>> user(@PathVariable int id) {
        return jdbcTemplate.queryForList(
            "SELECT id, email FROM users WHERE id = ?", id);
    }

    @GetMapping("/tasa/{iso2}")
    public List<Map<String, Object>> tasa(@PathVariable String iso2) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM ps_tasa_cambio WHERE ps_pais_iso2 = ?", iso2);
    }

    @GetMapping("/tasa-actual/{iso2}")
    public List<Map<String, Object>> tasaActual(@PathVariable String iso2) {
        return jdbcTemplate.queryForList(
            "SELECT ps_tasa_cambio_tipocambio FROM ps_tasa_cambio " +
            "WHERE ps_pais_iso2 = ? ORDER BY ps_tasa_cambio_id DESC LIMIT 1", iso2);
    }
}
