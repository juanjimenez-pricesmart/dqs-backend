package com.dqs.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP client for the GoSocket fiscal validation API.
 * Owns all transport concerns — auth, timeouts, error handling.
 * FiscalService calls this; it never touches HttpURLConnection directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoSocketClient {

    private final ObjectMapper objectMapper;

    @Value("${gosocket.base-url}")
    private String baseUrl;

    @Value("${gosocket.username}")
    private String username;

    @Value("${gosocket.password}")
    private String password;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAccount(String accountCode, String identificationType,
                                          String receiverCode, String countryId) {
        String path = "/api/v1/Account/GetAccount"
                + "?AccountCode=" + accountCode
                + "&identificationType=" + identificationType
                + "&receiverCode=" + receiverCode
                + "&countryId=" + countryId;

        String url = baseUrl + path;
        log.info("[GoSocketClient] GET {}", url);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth());

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            log.debug("[GoSocketClient] response status={}", status);
            return objectMapper.readValue(body, Map.class);

        } catch (Exception e) {
            log.error("[GoSocketClient] Error calling GoSocket: {}", e.getMessage());
            throw new RuntimeException("Error validando identificación fiscal: " + e.getMessage());
        }
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
