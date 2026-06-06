package com.dqs.api.controller;

import com.dqs.api.service.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
@Tag(name = "Ítems (catálogo)", description = "Consulta de productos e inventario vía Business API")
public class ItemController {

    private final ItemService itemService;

    @Operation(summary = "Buscar ítem por código", description = "Retorna precio, peso, impuestos y datos del producto para un club específico")
    @GetMapping("/{itemCode}/club/{clubId}")
    public ResponseEntity<Map<String, Object>> getItem(
            @Parameter(description = "Código del producto") @PathVariable String itemCode,
            @Parameter(description = "ID del club/tienda") @PathVariable Integer clubId) {
        log.info("[ItemController] GET /api/v1/items/{}/club/{}", itemCode, clubId);
        return ResponseEntity.ok(itemService.getItemByCode(itemCode, clubId));
    }

    @Operation(summary = "Buscar ítems por descripción", description = "Búsqueda de productos por texto en el nombre/descripción para un club")
    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @Parameter(description = "ID del club/tienda") @RequestParam @NotNull Integer clubId,
            @Parameter(description = "Texto a buscar") @RequestParam @NotNull String q) {
        log.info("[ItemController] GET /api/v1/items/search clubId={} q={}", clubId, q);
        return ResponseEntity.ok(itemService.searchItems(clubId, q));
    }
}
