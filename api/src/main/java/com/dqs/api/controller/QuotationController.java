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
    private final com.dqs.api.service.SeasonService seasonService;
    private final com.dqs.api.repository.QuotationListRepository quotationListRepository;
    private final com.dqs.api.service.BankTransferNoticeService bankTransferNoticeService;

    @Operation(summary = "List quotations for a store", description = "Legacy-parity quotations list. Period filter only applies to non-pending statuses; scope=mine restricts to the given userId")
    @GetMapping
    public ResponseEntity<List<java.util.Map<String, Object>>> listByStore(
            @RequestParam int storeId,
            @RequestParam(defaultValue = "1") int statusId,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) Integer periodId) {
        log.info("[QuotationController] GET /api/v1/quotations storeId={} statusId={} scope={} userId={} periodId={}",
                storeId, statusId, scope, userId, periodId);
        boolean mineOnly = "mine".equalsIgnoreCase(scope);
        return ResponseEntity.ok(
                quotationListRepository.findByStoreFiltered(storeId, statusId, mineOnly, userId, periodId));
    }

    @Operation(summary = "Deliveries of sold quotations", description = "Pending-shipment deliveries for quotations in Sale status (status=3), for the given club")
    @GetMapping("/deliveries")
    public ResponseEntity<List<java.util.Map<String, Object>>> listDeliveries(@RequestParam int storeId) {
        log.info("[QuotationController] GET /api/v1/quotations/deliveries storeId={}", storeId);
        return ResponseEntity.ok(quotationListRepository.findDeliveriesByStore(storeId));
    }

    @Operation(summary = "Quotations summary", description = "Count and total amount per status for the period; pending quotations are counted without a period restriction")
    @GetMapping("/summary")
    public ResponseEntity<List<java.util.Map<String, Object>>> summary(
            @RequestParam int storeId,
            @RequestParam(required = false) Integer periodId) {
        log.info("[QuotationController] GET /api/v1/quotations/summary storeId={} periodId={}", storeId, periodId);
        return ResponseEntity.ok(quotationListRepository.findSummaryByStatus(storeId, periodId));
    }

    @Operation(summary = "Monthly closing periods", description = "Periods from ps_cierre_mensual for the Period filter, most recent first")
    @GetMapping("/periods")
    public ResponseEntity<List<java.util.Map<String, Object>>> periods() {
        return ResponseEntity.ok(quotationListRepository.findPeriods());
    }

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

    @Operation(summary = "Razones de cancelación", description = "Lista de motivos disponibles para cancelar una cotización")
    @GetMapping("/cancel-reasons")
    public ResponseEntity<List<java.util.Map<String, Object>>> getCancelReasons() {
        return ResponseEntity.ok(quotationService.getCancelReasons());
    }

    @Operation(summary = "Cancelar cotización", description = "Cancela una cotización pendiente (status=1), marcándola como status=4 con un motivo")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Integer reasonId = body.get("reasonId") != null ? Integer.parseInt(body.get("reasonId").toString()) : null;
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/cancel reasonId={}", id, reasonId);
        quotationService.cancelQuotation(id, reasonId);
        return ResponseEntity.noContent().build();
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

    @Operation(summary = "Alta masiva de ítems",
               description = "Agrega varias líneas desde filas pegadas de una hoja de cálculo. El cliente envía solo código y cantidad; " +
                             "la búsqueda en catálogo ocurre en el servidor. Retorna los códigos agregados y los no encontrados.")
    @PostMapping("/{id}/items/bulk")
    public ResponseEntity<java.util.Map<String, Object>> addItemsBulk(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Integer clubId = Integer.valueOf(body.get("clubId").toString());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> lines =
                (List<java.util.Map<String, Object>>) body.getOrDefault("lines", List.of());
        log.info("[QuotationController] POST /api/v1/quotations/{}/items/bulk lines={}", id, lines.size());
        return ResponseEntity.ok(quotationService.addItemsBulk(id, clubId, lines));
    }

    /**
     * Advisory only, as legacy is: it warns under the payment method and never
     * blocks the close. Returns the figures rather than a sentence — the wording
     * is composed in the frontend through i18n, so both locales read correctly.
     */
    @Operation(summary = "Aviso de mínimo para Transferencia Bancaria",
               description = "B2B-676: indica si el total de la cotización alcanza el mínimo del país")
    @GetMapping("/{id}/bank-transfer-notice")
    public ResponseEntity<com.dqs.api.dto.BankTransferNoticeResponse> bankTransferNotice(
            @PathVariable Long id,
            @RequestParam String paymentMethod) {
        log.info("[QuotationController] GET /{}/bank-transfer-notice paymentMethod={}", id, paymentMethod);
        return ResponseEntity.ok(bankTransferNoticeService.check(id, paymentMethod));
    }

    @Operation(summary = "Guardar comentario de cabecera",
               description = "Guarda la nota general de la cotización, la que se imprime en el PDF. " +
                             "Equivale a orders/savecomment del legacy con item == 0. " +
                             "El comentario por línea va en PATCH /{id}/items/{itemId} con la clave comment.")
    @PatchMapping("/{id}/comment")
    public ResponseEntity<Void> updateComment(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        String comment = body.get("comment") != null ? body.get("comment").toString() : null;
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/comment length={}", id,
                comment == null ? 0 : comment.length());
        quotationService.updateComment(id, comment);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Temporadas disponibles",
               description = "Las temporadas activas asignadas al club — ps_temporada con status 2, cruzada con " +
                             "ps_temporada_tienda. Lista vacía si el club no tiene ninguna asignada, que es una " +
                             "respuesta válida: el select queda con solo su placeholder. El legacy no filtra por " +
                             "club y ofrece toda temporada activa a todos.")
    @GetMapping("/seasons")
    public ResponseEntity<List<java.util.Map<String, Object>>> seasons(
            @Parameter(description = "ID del club/tienda") @RequestParam Integer clubId) {
        log.info("[QuotationController] GET /api/v1/quotations/seasons clubId={}", clubId);
        return ResponseEntity.ok(seasonService.getActiveForClub(clubId));
    }

    @Operation(summary = "Asignar temporada",
               description = "Etiqueta la cotización con una temporada, o la quita enviando null. Equivale a " +
                             "orders/temporadaupdate del legacy, que no valida nada; acá la temporada tiene que " +
                             "estar activa y asignada al club de la cotización, o responde 400.")
    @PatchMapping("/{id}/season")
    public ResponseEntity<QuotationResponse> updateSeason(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Object raw = body.get("seasonId");
        Integer seasonId = raw == null ? null : Integer.valueOf(raw.toString());
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/season seasonId={}", id, seasonId);
        return ResponseEntity.ok(quotationService.updateSeason(id, seasonId));
    }

    @Operation(summary = "Extender fecha de expiración",
               description = "Suma 21 días a la fecha de expiración que ya tiene la cotización, no a hoy — " +
                             "llamarlo dos veces suma 42. Equivale a orders/extenderfecha del legacy: sin tope, " +
                             "sin validar el estado y sin bitácora. Una cotización sin fecha queda en hoy + 21.")
    @PatchMapping("/{id}/extend-expiry")
    public ResponseEntity<QuotationResponse> extendExpiry(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/extend-expiry", id);
        return ResponseEntity.ok(quotationService.extendExpiry(id));
    }

    @Operation(summary = "Listar ítems", description = "Retorna todos los ítems de la cotización ordenados por productId")
    @GetMapping("/{id}/items")
    public ResponseEntity<List<QuotationItemResponse>> getItems(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getItems(id));
    }

    @Operation(summary = "Actualizar una línea",
               description = "Actualiza cantidad, porcentaje de exención, comentario, la bandera de imagen y — para productos " +
                             "vendidos en denominaciones fijas, hoy solo la gift card 999979 — el monto elegido, en una sola " +
                             "llamada. Todos los campos son opcionales. La exención se limita al impuesto de la línea y " +
                             "recalcula el monto exento. La clave presetAmount se valida contra los montos configurados para " +
                             "el país del club: un producto que no usa montos fijos, o una cifra que no está en la lista, " +
                             "responde 400.")
    @PatchMapping("/{id}/items/{itemId}")
    public ResponseEntity<QuotationItemResponse> updateItem(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @Parameter(description = "ID del ítem") @PathVariable Long itemId,
            @RequestBody java.util.Map<String, Object> body) {
        log.info("[QuotationController] PATCH /api/v1/quotations/{}/items/{}", id, itemId);
        return ResponseEntity.ok(quotationService.updateItem(id, itemId, body));
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
