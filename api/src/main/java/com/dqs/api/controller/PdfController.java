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

    /**
     * @param sortBy legacy's "Ordenar por" radios — 1 department, 2 category,
     *               3 code, 4 description. Absent means department, which is
     *               the radio the edit screen starts on; anything outside 1-4
     *               falls back to code, as legacy's switch does.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @PathVariable Long id,
            @RequestParam(name = "sortBy", required = false) Integer sortBy) {
        log.info("[PdfController] GET /api/v1/quotations/{}/pdf?sortBy={}", id, sortBy);
        try {
            byte[] pdf = quotePdfService.generate(id, sortBy);
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
