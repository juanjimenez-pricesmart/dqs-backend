// controller/QuotationController.java — versión completa
package com.dqs.api.controller;

import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.CloseQuotationRequest;
import com.dqs.api.dto.QuotationItemRequest;
import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SendToOmsRequest;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.service.QuotationService;
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
public class QuotationController {

    private final QuotationService quotationService;

    @PostMapping
    public ResponseEntity<QuotationResponse> create(
            @Valid @RequestBody CreateQuotationRequest request) {

        log.info("[QuotationController] POST /api/v1/quotations store_id={}",
                request.getStoreId());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(quotationService.createQuotation(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuotationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getById(id));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<QuotationResponse> submit(
            @PathVariable Long id,
            @Valid @RequestBody SubmitQuotationRequest request) {

        log.info("[QuotationController] POST /api/v1/quotations/{}/submit", id);

        return ResponseEntity.ok(quotationService.submitQuotation(id, request));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<QuotationItemResponse> saveItem(
            @PathVariable Long id,
            @Valid @RequestBody QuotationItemRequest request) {

        log.info("[QuotationController] POST /api/v1/quotations/{}/items product_id={}",
                id, request.getProductId());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(quotationService.saveItem(id, request));
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<List<QuotationItemResponse>> getItems(@PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getItems(id));
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<QuotationResponse> close(
            @PathVariable Long id,
            @Valid @RequestBody CloseQuotationRequest request) {

        log.info("[QuotationController] PATCH /api/v1/quotations/{}/close", id);

        return ResponseEntity.ok(quotationService.closeQuotation(id, request));
    }

    @GetMapping("/{id}/oms-status")
    public ResponseEntity<Object> omsStatus(@PathVariable Long id) {
        log.info("[QuotationController] GET /api/v1/quotations/{}/oms-status", id);
        return ResponseEntity.ok(quotationService.getOmsStatus(id));
    }

    @PostMapping("/{id}/send-to-oms")
    public ResponseEntity<String> sendToOms(
            @PathVariable Long id,
            @Valid @RequestBody SendToOmsRequest request) {

        log.info("[QuotationController] POST /api/v1/quotations/{}/send-to-oms", id);

        String result = quotationService.sendToOms(id, request);
        return ResponseEntity.ok(result);
    }
}