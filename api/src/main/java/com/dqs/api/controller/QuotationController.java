// controller/QuotationController.java — versión completa
package com.dqs.api.controller;

import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.service.QuotationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}