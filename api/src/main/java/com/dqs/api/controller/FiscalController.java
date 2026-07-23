package com.dqs.api.controller;

import com.dqs.api.service.FiscalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/fiscal")
@RequiredArgsConstructor
@Tag(name = "Fiscal / FEL", description = "Validación fiscal (GoSocket) y datos FEL por cotización")
public class FiscalController {

    private final FiscalService fiscalService;

    // ── GoSocket Validation ───────────────────────────────────────────────────

    @Operation(summary = "Validar NIT o CUI", description = "type: 1 = NIT, 2 = CUI")
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestParam String code,
            @RequestParam(required = false) String type) {
        log.info("[FiscalController] GET /validate code={} type={}", code, type);
        return ResponseEntity.ok(fiscalService.validateIdentification(code, type));
    }

    // ── Save Fiscal Data ──────────────────────────────────────────────────────

    @Operation(summary = "Guardar datos fiscales FEL", description = "Upsert en quotation_fiscal + catálogos")
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        log.info("[FiscalController] POST /save quotationId={} membership={}", body.get("quotation_id"), body.get("membership"));
        boolean ok = fiscalService.saveFiscalData(body);
        if (!ok) return ResponseEntity.internalServerError()
            .body(Map.of("success", false, "message", "Error saving fiscal data"));
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Get Saved Record (for OMS / review) ──────────────────────────────────

    @Operation(summary = "Obtener datos fiscales por cotización")
    @GetMapping("/data")
    public ResponseEntity<?> getFiscalData(@RequestParam Long quotationId) {
        log.info("[FiscalController] GET /data quotationId={}", quotationId);
        Map<String, Object> result = fiscalService.getFiscalDataByQuotation(quotationId);
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }

    // ── Catalog Endpoints ─────────────────────────────────────────────────────

    @Operation(summary = "Actividades económicas por país")
    @GetMapping("/catalog/economic-activities")
    public ResponseEntity<List<Map<String, Object>>> economicActivities(@RequestParam String country) {
        return ResponseEntity.ok(fiscalService.getEconomicActivities(country));
    }

    @Operation(summary = "Ciudades por país")
    @GetMapping("/catalog/cities")
    public ResponseEntity<List<Map<String, Object>>> cities(@RequestParam String country) {
        return ResponseEntity.ok(fiscalService.getCities(country));
    }

    @Operation(summary = "Zonas por ciudad")
    @GetMapping("/catalog/zones")
    public ResponseEntity<List<Map<String, Object>>> zones(@RequestParam String cityCode) {
        return ResponseEntity.ok(fiscalService.getZones(cityCode));
    }

    @Operation(summary = "Colonias/barrios por zona y ciudad")
    @GetMapping("/catalog/neighborhoods")
    public ResponseEntity<List<Map<String, Object>>> neighborhoods(
            @RequestParam String zoneCode,
            @RequestParam String cityCode) {
        return ResponseEntity.ok(fiscalService.getNeighborhoods(zoneCode, cityCode));
    }

    @Operation(summary = "Tipos de documento fiscal por país (ps_fel)")
    @GetMapping("/catalog/doc-types")
    public ResponseEntity<List<Map<String, Object>>> docTypes(@RequestParam String country) {
        return ResponseEntity.ok(fiscalService.getDocTypes(country));
    }
}
