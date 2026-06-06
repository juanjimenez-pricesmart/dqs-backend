package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class FiscalService {

    private final ObjectMapper objectMapper;

    @Value("${gosocket.base-url}")
    private String gosocketBaseUrl;

    @Value("${gosocket.account-code}")
    private String accountCode;

    @Value("${gosocket.username}")
    private String username;

    @Value("${gosocket.password}")
    private String password;

    @Value("${gosocket.country-id}")
    private String countryId;

    public FiscalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates a fiscal identification (NIT, CUI, RUT) via GoSocket.
     *
     * @param receiverCode     the NIT/CUI/RUT value
     * @param identificationType NIT | CUI | RUT
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validateIdentification(String receiverCode, String identificationType) {
        log.info("[FiscalService] validateIdentification code={} type={}", receiverCode, identificationType);

        String path = "/api/v1/Account/GetAccount"
                + "?AccountCode=" + accountCode
                + "&identificationType=" + identificationType
                + "&receiverCode=" + receiverCode
                + "&countryId=" + countryId;

        String url = gosocketBaseUrl + path;
        log.info("[FiscalService] GET {}", url);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");

            String credentials = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + credentials);

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            log.debug("[FiscalService] GoSocket response status={}", status);
            return objectMapper.readValue(body, Map.class);

        } catch (Exception e) {
            log.error("[FiscalService] Error calling GoSocket: {}", e.getMessage());
            throw new RuntimeException("Error validando identificación fiscal: " + e.getMessage());
        }
    }
}
