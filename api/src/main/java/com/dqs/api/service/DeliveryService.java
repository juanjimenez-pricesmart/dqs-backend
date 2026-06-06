package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;

    /**
     * Returns available delivery/pickup windows for a club on a given date.
     *
     * @param clubId       store/club ID
     * @param dateTime     ISO datetime string, e.g. "2024-06-10T08:00:00"
     * @param deliveryType PICK_UP_IN_CLUB | B2B_DELIVERY | etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeliveryWindows(Integer clubId, String dateTime, String deliveryType) {
        log.info("[DeliveryService] getDeliveryWindows clubId={} dateTime={} type={}", clubId, dateTime, deliveryType);

        String type = (deliveryType != null && !deliveryType.isBlank()) ? deliveryType : "PICK_UP_IN_CLUB";
        String path = "/api/deliveries/location/" + clubId
                + "/window-place-time/" + dateTime + "Z"
                + "/delivery/" + type;

        String response = businessApiClient.post(path, Map.of());
        try {
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[DeliveryService] Error parsing delivery windows: {}", e.getMessage());
            throw new RuntimeException("Error consultando ventanas de entrega: " + e.getMessage());
        }
    }
}
