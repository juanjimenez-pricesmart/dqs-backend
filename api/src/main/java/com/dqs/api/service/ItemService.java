package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getItemByCode(String itemCode, Integer clubId) {
        log.info("[ItemService] getItemByCode itemCode={} clubId={}", itemCode, clubId);
        String response = businessApiClient.get("/api/getItemCode/" + itemCode + "/club/" + clubId);
        try {
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[ItemService] Error parsing item response: {}", e.getMessage());
            throw new RuntimeException("Error consultando item: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Object searchItems(Integer clubId, String description) {
        log.info("[ItemService] searchItems clubId={} description={}", clubId, description);
        String encoded = description.replace(" ", "%20");
        String response = businessApiClient.get("/api/getSearch/club/" + clubId + "/description/" + encoded);
        try {
            // The business API may return an array or an object
            String trimmed = response.trim();
            if (trimmed.startsWith("[")) {
                return objectMapper.readValue(response, Object[].class);
            }
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[ItemService] Error parsing search response: {}", e.getMessage());
            throw new RuntimeException("Error buscando items: " + e.getMessage());
        }
    }
}
