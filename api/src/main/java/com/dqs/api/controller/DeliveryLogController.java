package com.dqs.api.controller;

import com.dqs.api.dto.DeliveryLogResponse;
import com.dqs.api.service.DeliveryLogService;
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
@RequestMapping("/api/v1/delivery-logs")
@RequiredArgsConstructor
@Tag(name = "Delivery Logs", description = "Dispatch log management for delivered quotations")
public class DeliveryLogController {

    private final DeliveryLogService deliveryLogService;
    private final com.dqs.api.service.DeliveryLogPdfService pdfService;

    @Operation(summary = "Deliveries not yet assigned to any log, filtered by route")
    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> getAvailable(
            @RequestParam int storeId,
            @RequestParam String routeId) {
        log.info("[DeliveryLogController] GET /available storeId={} routeId={}", storeId, routeId);
        return ResponseEntity.ok(deliveryLogService.getAvailableDeliveries(storeId, routeId));
    }

    @Operation(summary = "Create a new delivery log and link quotations to it")
    @PostMapping
    public ResponseEntity<DeliveryLogResponse> create(@RequestBody Map<String, Object> body) {
        int storeId         = toInt(body.get("storeId"));
        int createdBy       = toInt(body.getOrDefault("createdBy", 1));
        String routeId      = body.getOrDefault("routeId", "").toString();
        List<Long> quotationIds = toIdList(body.get("quotationIds"));
        log.info("[DeliveryLogController] POST / storeId={} routeId={} quotations={}", storeId, routeId, quotationIds);
        DeliveryLogResponse result = deliveryLogService.createLog(storeId, quotationIds, createdBy, routeId);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Add more quotations to an existing log")
    @PostMapping("/{logId}/add")
    public ResponseEntity<DeliveryLogResponse> addToLog(
            @PathVariable long logId,
            @RequestBody Map<String, Object> body) {
        List<Long> quotationIds = toIdList(body.get("quotationIds"));
        log.info("[DeliveryLogController] POST /{}/add quotations={}", logId, quotationIds);
        return ResponseEntity.ok(deliveryLogService.addToLog(logId, quotationIds));
    }

    @Operation(summary = "Active delivery logs for a store filtered by route")
    @GetMapping("/active")
    public ResponseEntity<List<DeliveryLogResponse>> getActive(
            @RequestParam int storeId,
            @RequestParam String routeId) {
        log.info("[DeliveryLogController] GET /active storeId={} routeId={}", storeId, routeId);
        return ResponseEntity.ok(deliveryLogService.getActiveLogs(storeId, routeId));
    }

    @Operation(summary = "Close a delivery log (mark as sent)")
    @PatchMapping("/{logId}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable long logId) {
        log.info("[DeliveryLogController] PATCH /{}/close", logId);
        boolean ok = deliveryLogService.closeLog(logId);
        return ResponseEntity.ok(Map.of("success", ok));
    }

    @Operation(summary = "Historical (closed) delivery logs for a store filtered by route")
    @GetMapping("/historical")
    public ResponseEntity<List<DeliveryLogResponse>> getHistorical(
            @RequestParam int storeId,
            @RequestParam String routeId) {
        log.info("[DeliveryLogController] GET /historical storeId={} routeId={}", storeId, routeId);
        return ResponseEntity.ok(deliveryLogService.getHistoricalLogs(storeId, routeId));
    }

    @Operation(summary = "Download delivery log as PDF")
    @GetMapping("/{logId}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable long logId) throws Exception {
        log.info("[DeliveryLogController] GET /{}/pdf", logId);
        byte[] pdfBytes = pdfService.generatePdf(logId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"delivery-log-" + logId + ".pdf\"")
                .body(pdfBytes);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Long> toIdList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream()
                .map(e -> e instanceof Number ? ((Number) e).longValue() : Long.parseLong(e.toString()))
                .toList();
        }
        return List.of();
    }
}
