package com.dqs.api.controller;

import com.dqs.api.service.QuotePdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/quotations")
@RequiredArgsConstructor
public class PdfController {

    private final QuotePdfService quotePdfService;

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        log.info("[PdfController] GET /api/v1/quotations/{}/pdf", id);
        try {
            byte[] pdf = quotePdfService.generate(id);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quote-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
        } catch (Exception e) {
            log.error("[PdfController] Error generating PDF for quotationId={}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
