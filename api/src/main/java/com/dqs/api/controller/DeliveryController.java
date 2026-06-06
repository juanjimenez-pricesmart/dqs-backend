package com.dqs.api.controller;

import com.dqs.api.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    /**
     * GET /api/v1/deliveries?clubId=6001&dateTime=2024-06-10T08:00:00&type=PICK_UP_IN_CLUB
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getDeliveryWindows(
            @RequestParam Integer clubId,
            @RequestParam String dateTime,
            @RequestParam(defaultValue = "PICK_UP_IN_CLUB") String type) {

        log.info("[DeliveryController] GET /api/v1/deliveries clubId={} dateTime={} type={}", clubId, dateTime, type);
        return ResponseEntity.ok(deliveryService.getDeliveryWindows(clubId, dateTime, type));
    }
}
