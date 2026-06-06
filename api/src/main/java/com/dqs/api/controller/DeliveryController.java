package com.dqs.api.controller;

import com.dqs.api.service.DeliveryService;
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
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
@Tag(name = "Entregas", description = "Consulta de ventanas de entrega y pickup vía Business API")
public class DeliveryController {

    private final DeliveryService deliveryService;

    @Operation(
        summary = "Ventanas de entrega / pickup",
        description = "Retorna los slots de tiempo disponibles para un club en una fecha dada. Tipos: PICK_UP_IN_CLUB, B2B_DELIVERY"
    )
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDeliveryWindows(
            @Parameter(description = "ID del club/tienda") @RequestParam Integer clubId,
            @Parameter(description = "Fecha y hora ISO, ej: 2026-06-10T08:00:00") @RequestParam String dateTime,
            @Parameter(description = "Tipo de entrega (PICK_UP_IN_CLUB | B2B_DELIVERY)") @RequestParam(defaultValue = "PICK_UP_IN_CLUB") String type) {
        log.info("[DeliveryController] GET /api/v1/deliveries clubId={} dateTime={} type={}", clubId, dateTime, type);
        return ResponseEntity.ok(deliveryService.getDeliveryWindows(clubId, dateTime, type));
    }
}
