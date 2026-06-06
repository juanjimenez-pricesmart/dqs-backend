package com.dqs.api.controller;

import com.dqs.api.service.GlobalPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Pagos (GlobalPay)", description = "Integración con el procesador de pagos GlobalPay")
public class PaymentController {

    private final GlobalPayService globalPayService;

    @Operation(
        summary = "Obtener token de pago",
        description = "Genera el token de autenticación GlobalPay para el widget de pago en el frontend. Las credenciales se leen de ps_globalpay_credenciales según el storeId."
    )
    @GetMapping("/globalpay/token")
    public ResponseEntity<Map<String, Object>> getToken(
            @Parameter(description = "ID del club/tienda") @RequestParam Integer storeId) {
        log.info("[PaymentController] GET /api/v1/payments/globalpay/token storeId={}", storeId);
        return ResponseEntity.ok(globalPayService.getPaymentToken(storeId));
    }

    @Operation(
        summary = "Callback de GlobalPay (webhook)",
        description = "Endpoint que GlobalPay llama al completar una transacción. Actualiza el paidStatus de la cotización. status: 4 = pagado, 3 = fallido."
    )
    @PostMapping("/globalpay/callback")
    public ResponseEntity<Void> callback(@RequestBody Map<String, Object> body) {
        log.info("[PaymentController] POST /api/v1/payments/globalpay/callback");
        globalPayService.processCallback(body);
        return ResponseEntity.ok().build();
    }
}
