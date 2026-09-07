package com.dqs.api.controller;

import com.dqs.api.service.DeliveryService;
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

    // ── Routes ────────────────────────────────────────────────────────────────

    @Operation(summary = "Active delivery routes for a store")
    @GetMapping("/routes")
    public ResponseEntity<List<com.dqs.api.catalog.source.RouteInfo>> getRoutes(@RequestParam Integer storeId) {
        log.info("[DeliveryController] GET /routes storeId={}", storeId);
        return ResponseEntity.ok(deliveryService.getRoutes(storeId));
    }

    // ── quotation_delivery ────────────────────────────────────────────────────

    @Operation(summary = "Guardar datos de delivery para una cotización")
    @GetMapping("/last-address")
    public ResponseEntity<Map<String, Object>> getLastAddress(@RequestParam String membership) {
        return ResponseEntity.ok(Map.of("address", deliveryService.getLastDeliveryAddress(membership)));
    }

    // saveDelivery and deleteDelivery let their exception out so the
    // transaction actually rolls back — swallowing it inside the @Transactional
    // method would leave a half-written delivery and then fail at commit time
    // anyway. The translation to a response body stays here, unchanged, so the
    // frontend sees exactly the status and payload it saw before.

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        log.info("[DeliveryController] POST / quotationId={}", body.get("quotation_id"));
        try {
            deliveryService.saveDelivery(body);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[DeliveryController] Error saving delivery: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "Error saving delivery"));
        }
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
        try {
            deliveryService.deleteDelivery(quotationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[DeliveryController] Error deleting delivery: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("success", false));
        }
    }
}
