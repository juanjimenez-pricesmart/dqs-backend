package com.dqs.api.controller;

import com.dqs.api.service.FiscalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/fiscal")
@RequiredArgsConstructor
public class FiscalController {

    private final FiscalService fiscalService;

    /**
     * GET /api/v1/fiscal/validate?code=123456&type=NIT
     * Types: NIT (Guatemala), CUI (Costa Rica), RUT
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestParam String code,
            @RequestParam(defaultValue = "NIT") String type) {

        log.info("[FiscalController] GET /api/v1/fiscal/validate code={} type={}", code, type);
        return ResponseEntity.ok(fiscalService.validateIdentification(code, type));
    }
}
