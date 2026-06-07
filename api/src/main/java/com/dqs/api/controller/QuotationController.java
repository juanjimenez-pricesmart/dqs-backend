package com.dqs.api.controller;

import com.dqs.api.dto.*;
import com.dqs.api.service.QuotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/quotations")
@RequiredArgsConstructor
@Tag(name = "Cotizaciones", description = "Gestión del ciclo de vida de cotizaciones")
public class QuotationController {

    private final QuotationService quotationService;

    @Operation(summary = "Crear cotización", description = "Crea una nueva cotización en estado borrador (status=1)")
    @PostMapping
    public ResponseEntity<QuotationResponse> create(@Valid @RequestBody CreateQuotationRequest request) {
        log.info("[QuotationController] POST /api/v1/quotations store_id={}", request.getStoreId());
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.createQuotation(request));
    }

    @Operation(summary = "Obtener cotización", description = "Retorna una cotización con todos sus datos agrupados")
    @GetMapping("/{id}")
    public ResponseEntity<QuotationResponse> getById(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getById(id));
    }

    @Operation(summary = "Enviar cotización (submit)", description = "Cambia el estado de borrador (1) a enviada (2)")
    @PostMapping("/{id}/submit")
    public ResponseEntity<QuotationResponse> submit(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Valid @RequestBody SubmitQuotationRequest request) {
        log.info("[QuotationController] POST /api/v1/quotations/{}/submit", id);
        return ResponseEntity.ok(quotationService.submitQuotation(id, request));
    }

    @Operation(summary = "Agregar / actualizar ítem", description = "Upsert de un ítem en la cotización. Si el producto ya existe, actualiza la cantidad.")
    @PostMapping("/{id}/items")
    public ResponseEntity<QuotationItemResponse> saveItem(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Valid @RequestBody QuotationItemRequest request) {
        log.info("[QuotationController] POST /api/v1/quotations/{}/items product_id={}", id, request.getProductId());
        return ResponseEntity.status(HttpStatus.CREATED).body(quotationService.saveItem(id, request));
    }

    @Operation(summary = "Listar ítems", description = "Retorna todos los ítems de la cotización ordenados por productId")
    @GetMapping("/{id}/items")
    public ResponseEntity<List<QuotationItemResponse>> getItems(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getItems(id));
    }

    @Operation(summary = "Actualizar cantidad de ítem", description = "Actualiza solo la cantidad y recalcula el monto")
    @PatchMapping("/{id}/items/{itemId}/qty")
    public ResponseEntity<QuotationItemResponse> updateItemQty(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Parameter(description = "ID del ítem") @PathVariable Long itemId,
            @RequestBody java.util.Map<String, Object> body) {
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/items/{}/qty", id, itemId);
        return ResponseEntity.ok(quotationService.updateItemQty(id, itemId, body));
    }

    @Operation(summary = "Eliminar ítem", description = "Elimina un ítem de la cotización por su ID de ítem")
    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Parameter(description = "ID del ítem") @PathVariable Long itemId) {
        log.info("[QuotationController] DELETE /api/v1/quotations/{}/items/{}", id, itemId);
        quotationService.deleteItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cerrar cotización", description = "Cambia el estado a cerrada/venta (3) y registra los datos de pago")
    @PatchMapping("/{id}/close")
    public ResponseEntity<QuotationResponse> close(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Valid @RequestBody CloseQuotationRequest request) {
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/close", id);
        return ResponseEntity.ok(quotationService.closeQuotation(id, request));
    }

    @Operation(summary = "Enviar a OMS", description = "Construye el payload y envía la cotización (status=3) al Order Management System")
    @PostMapping("/{id}/send-to-oms")
    public ResponseEntity<String> sendToOms(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Valid @RequestBody SendToOmsRequest request) {
        log.info("[QuotationController] POST /api/v1/quotations/{}/send-to-oms", id);
        return ResponseEntity.ok(quotationService.sendToOms(id, request));
    }

    @Operation(summary = "Status en OMS", description = "Consulta el historial de estados de la orden en el OMS usando el quoteNo")
    @GetMapping("/{id}/oms-status")
    public ResponseEntity<Object> omsStatus(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        log.info("[QuotationController] GET /api/v1/quotations/{}/oms-status", id);
        return ResponseEntity.ok(quotationService.getOmsStatus(id));
    }
}
