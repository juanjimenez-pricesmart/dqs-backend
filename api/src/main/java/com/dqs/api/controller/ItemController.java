package com.dqs.api.controller;

import com.dqs.api.service.ItemService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @GetMapping("/{itemCode}/club/{clubId}")
    public ResponseEntity<Map<String, Object>> getItem(
            @PathVariable String itemCode,
            @PathVariable Integer clubId) {

        log.info("[ItemController] GET /api/v1/items/{}/club/{}", itemCode, clubId);
        return ResponseEntity.ok(itemService.getItemByCode(itemCode, clubId));
    }

    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @RequestParam @NotNull Integer clubId,
            @RequestParam @NotNull String q) {

        log.info("[ItemController] GET /api/v1/items/search clubId={} q={}", clubId, q);
        return ResponseEntity.ok(itemService.searchItems(clubId, q));
    }
}
