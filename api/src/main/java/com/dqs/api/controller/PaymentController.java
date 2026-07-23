package com.dqs.api.controller;

import com.dqs.api.service.PriceSmartPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Pagos", description = "Integración con PriceSmart Payments Service")
public class PaymentController {

    private final PriceSmartPaymentService paymentService;
    private final com.dqs.api.repository.PaymentMethodRepository paymentMethodRepository;

    @Operation(
        summary = "Métodos de pago por país",
        description = "Lista los métodos de pago disponibles (orders_pago) para el ISO2 de país indicado"
    )
    @GetMapping("/methods")
    public ResponseEntity<java.util.List<Map<String, Object>>> getMethods(@RequestParam String country) {
        log.info("[PaymentController] GET /api/v1/payments/methods country={}", country);
        return ResponseEntity.ok(paymentMethodRepository.findByCountry(country));
    }

    @Operation(
        summary = "Crear solicitud de pago",
        description = "Crea una Payment Request en el PriceSmart Payments Service y retorna el authorizationToken + iframeUrl para montar el widget de pago."
    )
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> createRequest(
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {

        Long quotationId = Long.parseLong(body.get("quotationId").toString());
        String ipAddress = req.getRemoteAddr();

        log.info("[PaymentController] POST /api/v1/payments/request quotationId={} ip={}", quotationId, ipAddress);
        return ResponseEntity.ok(paymentService.createPaymentRequest(quotationId, ipAddress));
    }

    @Operation(
        summary = "Consultar estado del pago",
        description = "Proxies GET /api/wallet/get-status del PriceSmart Payments Service. status_code AUTHORIZED = aprobado."
    )
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam String invoiceId) {
        log.info("[PaymentController] GET /api/v1/payments/status invoiceId={}", invoiceId);
        return ResponseEntity.ok(paymentService.getPaymentStatus(invoiceId));
    }

    @Operation(
        summary = "Callback de PriceSmart Payments (webhook)",
        description = "El PriceSmart Payments Service llama aquí al completar una transacción. Se actualiza el estado de la cotización."
    )
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestBody(required = false) Map<String, Object> body,
                                          @RequestParam Map<String, String> params) {
        log.info("[PaymentController] POST /api/v1/payments/callback body={} params={}", body, params);
        // TODO: parse success/error from params and update quotation paid status
        // success=true&invoiceId=xxx  OR  error=1001&invoiceId=xxx
        return ResponseEntity.ok().build();
    }
}
