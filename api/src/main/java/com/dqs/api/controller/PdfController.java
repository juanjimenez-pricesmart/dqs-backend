package com.dqs.api.controller;

import com.dqs.api.service.CallejasReportService;
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
    private final CallejasReportService callejasReportService;

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

    /**
     * The Callejas picking sheet — legacy's orders/reportecallejas/{id}/1, which
     * renders HTML and asks the browser to print it. A PDF here, as for the
     * quotation itself.
     *
     * 404 when the quotation did not come from a Callejas purchase order: there
     * is no sheet to print, and the button is only shown for those anyway.
     */
    @GetMapping("/{id}/callejas-report")
    public ResponseEntity<byte[]> callejasReport(@PathVariable Long id) {
        log.info("[PdfController] GET /api/v1/quotations/{}/callejas-report", id);
        try {
            byte[] pdf = callejasReportService.generate(id);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"callejas-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
        } catch (com.dqs.api.exception.QuotationNotFoundException e) {
            log.warn("[PdfController] No Callejas report for quotationId={}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[PdfController] Error generating Callejas report for quotationId={}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
