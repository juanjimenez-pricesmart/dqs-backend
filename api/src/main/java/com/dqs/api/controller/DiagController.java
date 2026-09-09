package com.dqs.api.controller;

import lombok.RequiredArgsConstructor;
import com.dqs.api.repository.support.NativeQueries;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/diag")
@RequiredArgsConstructor
public class DiagController {

    private final NativeQueries nativeQueries;

    @GetMapping("/tables")
    public List<String> tables() {
        return nativeQueries.column(
            "SHOW TABLES LIKE 'ps_%'", String.class);
    }

    /**
     * `peso` is the store's weight unit, and the totals block needs it: legacy
     * prints it in the label — "Total Peso (Kg)" — from the same column
     * (model_stores::getStoreSMetrico, rendered raw in edit.php:547).
     *
     * Its values are not clean — Kg, Kgs and Lbs all occur — and it is returned
     * as stored rather than normalised, because that is what the legacy label
     * shows. Note the separate bug this exposes: legacy's weight arithmetic
     * compares `strtoupper($smpeso) == 'KG'`, so the one store recorded as
     * "Kgs" is treated as pounds. Not fixed here; recorded so it is not
     * mistaken for something this change introduced.
     */
    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        return nativeQueries.list(
            "SELECT ps_tienda_id, nombre, pais, pais_iso2, idioma, moneda, peso, status FROM ps_tienda ORDER BY pais, nombre");
    }

    @GetMapping("/store/{id}")
    public List<Map<String, Object>> store(@PathVariable int id) {
        return nativeQueries.list(
            "SELECT * FROM ps_tienda WHERE ps_tienda_id = ?", id);
    }

    @GetMapping("/user/{id}")
    public List<Map<String, Object>> user(@PathVariable int id) {
        return nativeQueries.list(
            "SELECT id, email FROM users WHERE id = ?", id);
    }

    @GetMapping("/tasa/{iso2}")
    public List<Map<String, Object>> tasa(@PathVariable String iso2) {
        return nativeQueries.list(
            "SELECT * FROM ps_tasa_cambio WHERE ps_pais_iso2 = ?", iso2);
    }

    @GetMapping("/tasa-actual/{iso2}")
    public List<Map<String, Object>> tasaActual(@PathVariable String iso2) {
        return nativeQueries.list(
            "SELECT ps_tasa_cambio_tipocambio FROM ps_tasa_cambio " +
            "WHERE ps_pais_iso2 = ? ORDER BY ps_tasa_cambio_id DESC LIMIT 1", iso2);
    }
}
