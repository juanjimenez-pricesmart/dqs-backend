package com.dqs.api.controller;

import com.dqs.api.service.FiscalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/fiscal")
@RequiredArgsConstructor
@Tag(name = "Validación fiscal", description = "Validación de identificaciones fiscales (NIT/CUI) vía GoSocket")
public class FiscalController {

    private final FiscalService fiscalService;

    @Operation(
        summary = "Validar identificación fiscal",
        description = "Valida un NIT o CUI contra GoSocket. type: 1 = NIT (Guatemala), 2 = CUI. Si se omite type, usa el valor por defecto de configuración."
    )
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @Parameter(description = "Número de NIT o CUI a validar") @RequestParam String code,
            @Parameter(description = "Tipo de identificación: 1 = NIT, 2 = CUI") @RequestParam(required = false) String type) {
        log.info("[FiscalController] GET /api/v1/fiscal/validate code={} type={}", code, type);
        return ResponseEntity.ok(fiscalService.validateIdentification(code, type));
    }
}
