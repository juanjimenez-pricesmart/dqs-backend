package com.dqs.api.controller;

import com.dqs.api.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
@Tag(name = "Entregas", description = "Ventanas de entrega y datos de delivery por cotización")
public class DeliveryController {

    private final DeliveryService deliveryService;

    // ── Business API: delivery windows ────────────────────────────────────────

    @Operation(summary = "Ventanas de entrega / pickup disponibles")
    @GetMapping("/windows")
    public ResponseEntity<Map<String, Object>> getDeliveryWindows(
            @RequestParam Integer clubId,
            @RequestParam String dateTime,
            @RequestParam(defaultValue = "PICK_UP_IN_CLUB") String type) {
        log.info("[DeliveryController] GET /windows clubId={} dateTime={} type={}", clubId, dateTime, type);
        return ResponseEntity.ok(deliveryService.getDeliveryWindows(clubId, dateTime, type));
    }

    // ── quotation_delivery ────────────────────────────────────────────────────

    @Operation(summary = "Guardar datos de delivery para una cotización")
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        log.info("[DeliveryController] POST / quotationId={}", body.get("quotation_id"));
        boolean ok = deliveryService.saveDelivery(body);
        if (!ok) return ResponseEntity.internalServerError()
            .body(Map.of("success", false, "message", "Error saving delivery"));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @Operation(summary = "Obtener datos de delivery por cotización")
    @GetMapping("/{quotationId}")
    public ResponseEntity<?> get(@PathVariable Long quotationId) {
        log.info("[DeliveryController] GET /{}", quotationId);
        Map<String, Object> result = deliveryService.getDelivery(quotationId);
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Eliminar datos de delivery de una cotización")
    @DeleteMapping("/{quotationId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long quotationId) {
        log.info("[DeliveryController] DELETE /{}", quotationId);
        boolean ok = deliveryService.deleteDelivery(quotationId);
        return ResponseEntity.ok(Map.of("success", ok));
    }
}
