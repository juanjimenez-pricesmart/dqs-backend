package com.dqs.api.controller;

import com.dqs.api.dto.QuotationDocumentResponse;
import com.dqs.api.service.QuotationDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/quotations")
@RequiredArgsConstructor
@Tag(name = "Documentos de la cotización", description = "Comprobante de pago y otros adjuntos")
public class QuotationDocumentController {

    private final QuotationDocumentService documentService;

    @Operation(summary = "Adjuntar comprobante de pago",
               description = "Sube un archivo a S3 y lo registra en la cotización. JPG, GIF, PNG o PDF, " +
                             "máximo 4Mb — los mismos límites del legacy. Se puede llamar varias veces: " +
                             "cada archivo es una fila, no reemplaza al anterior. Responde 400 si el archivo " +
                             "no cumple o si el almacenamiento está apagado en este ambiente.")
    @PostMapping(value = "/{id}/vouchers", consumes = "multipart/form-data")
    public ResponseEntity<QuotationDocumentResponse> uploadVoucher(
            @Parameter(description = "ID de la cotización") @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Usuario que adjunta; opcional hasta que exista autenticación")
            @RequestParam(value = "userId", required = false) Long userId) {
        log.info("[QuotationDocumentController] POST /api/v1/quotations/{}/vouchers name={} bytes={}",
                id, file.getOriginalFilename(), file.getSize());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.uploadVoucher(id, file, userId));
    }

    @Operation(summary = "Listar comprobantes de pago",
               description = "Los comprobantes ya adjuntos a la cotización, en el orden en que se subieron. " +
                             "La pantalla los usa para saber si la puerta de cierre está satisfecha, que es " +
                             "algo que el legacy solo sabe dentro de la sesión del navegador.")
    @GetMapping("/{id}/vouchers")
    public ResponseEntity<List<QuotationDocumentResponse>> listVouchers(
            @Parameter(description = "ID de la cotización") @PathVariable Long id) {
        return ResponseEntity.ok(documentService.listVouchers(id));
    }
}
