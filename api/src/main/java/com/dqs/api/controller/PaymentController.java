package com.dqs.api.controller;

import com.dqs.api.service.GlobalPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final GlobalPayService globalPayService;

    /**
     * GET /api/v1/payments/globalpay/token?storeId=6001
     * Returns the GlobalPay auth token for the frontend payment widget.
     */
    @GetMapping("/globalpay/token")
    public ResponseEntity<Map<String, Object>> getToken(@RequestParam Integer storeId) {
        log.info("[PaymentController] GET /api/v1/payments/globalpay/token storeId={}", storeId);
        return ResponseEntity.ok(globalPayService.getPaymentToken(storeId));
    }

    /**
     * POST /api/v1/payments/globalpay/callback
     * Webhook called by GlobalPay when a transaction is completed.
     * Body: { "transaction": { "dev_reference": "{quotationId}", "status": 4, ... } }
     */
    @PostMapping("/globalpay/callback")
    public ResponseEntity<Void> callback(@RequestBody Map<String, Object> body) {
        log.info("[PaymentController] POST /api/v1/payments/globalpay/callback");
        globalPayService.processCallback(body);
        return ResponseEntity.ok().build();
    }
}
