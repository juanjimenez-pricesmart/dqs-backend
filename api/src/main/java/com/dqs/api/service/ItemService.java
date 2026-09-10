package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
            String trimmed = response.trim();
            Map<String, Object> item;
            if (trimmed.startsWith("[")) {
                // Business API returns an array for some items — take the first element
                // Jackson answers a `[`-prefixed body with an array or throws, so the
                // only empty case worth checking is a zero-length one.
                Map<String, Object>[] arr = objectMapper.readValue(trimmed, Map[].class);
                if (arr.length == 0) {
                    throw new RuntimeException("Item not found: " + itemCode);
                }
                item = arr[0];
            } else {
                item = objectMapper.readValue(trimmed, Map.class);
            }

            Map<String, Object> result = new LinkedHashMap<>(item);
            result.put("clubsOnhand", clubsOnhand(itemCode, clubId));
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[ItemService] Error parsing item response: {}", e.getMessage());
            throw new RuntimeException("Error consultando item: " + e.getMessage());
        }
    }

    /**
     * Stock of the item across every club in the country.
     *
     * A second Business API call, because getItemCode answers for one club: its
     * array form carries alternate rows for the same club, not one row per club,
     * so the country-wide figures cannot be derived from it. Legacy reads the
     * same endpoint from a separate browser request
     * (orders/onhandporpais → getcountryonhand in gettoken_helper.php); this
     * rides along on the item lookup instead, which is what the frontend's
     * `clubsOnhand` field already expects.
     *
     * A failure here returns an empty list rather than propagating: the panel
     * degrades to showing no per-club badges, and an item lookup — the hot path
     * of every quote — must not fail because a secondary display detail is
     * unavailable.
     */
    private List<Map<String, Object>> clubsOnhand(String itemCode, Integer clubId) {
        List<Map<String, Object>> clubs = new ArrayList<>();
        try {
            String body = businessApiClient.get("/api/getQuantity/" + itemCode + "/club/" + clubId);
            for (Map<String, Object> row : quantityRows(body.trim(), itemCode)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("costCenter", str(row.get("cost_center")));
                entry.put("quantityOnHand", row.get("qtyOnHand"));
                clubs.add(entry);
            }
        } catch (Exception e) {
            log.warn("[ItemService] clubsOnhand unavailable for itemCode={} clubId={}: {}",
                     itemCode, clubId, e.getMessage());
        }
        return clubs;
    }

    /**
     * The per-club rows out of a getQuantity payload.
     *
     * The endpoint answers with an object — {@code total_Quantity} beside a
     * {@code listQuantityCountry} array — which is what legacy reads
     * (views/orders/createbb.php iterates response.listQuantityCountry and
     * renders total_Quantity as its own badge). The bare-array branch is kept
     * because the sibling getItemCode endpoint demonstrably varies its shape
     * per item, and an unexpected array here should degrade to no badges rather
     * than throw.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> quantityRows(String body, String itemCode) throws Exception {
        if (body.startsWith("[")) {
            return Arrays.asList(objectMapper.readValue(body, Map[].class));
        }
        Map<String, Object> payload = objectMapper.readValue(body, Map.class);
        Object rows = payload.get("listQuantityCountry");
        if (rows instanceof List<?> list) return (List<Map<String, Object>>) list;
        log.warn("[ItemService] getQuantity itemCode={} carried no listQuantityCountry", itemCode);
        return List.of();
    }

    /** costCenter is a club number, rendered as a badge label — the frontend types it as a string. */
    private static String str(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return String.valueOf(n.longValue());
        return value.toString();
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
