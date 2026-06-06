package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Shared HTTP client for the Business API.
 * Handles OAuth2 token acquisition and proxied GET/POST calls.
 */
@Slf4j
@Component
public class BusinessApiClient {

    private final ObjectMapper objectMapper;

    @Value("${idp.base-url}")
    private String idpBaseUrl;

    @Value("${idp.client-id}")
    private String clientId;

    @Value("${idp.client-secret}")
    private String clientSecret;

    @Value("${business.api.membership-url}")
    private String businessBaseUrl;

    public BusinessApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        disableSslVerification();
    }

    public String get(String path) {
        String token = acquireToken();
        String url = businessBaseUrl + path + "?access_token=" + token;
        log.info("[BusinessApiClient] GET {}", url);
        try {
            HttpURLConnection conn = openConnection(url, "GET");
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            log.debug("[BusinessApiClient] GET {} → {} {}", path, status, body);
            return body;
        } catch (Exception e) {
            log.error("[BusinessApiClient] GET {} failed: {}", path, e.getMessage());
            throw new RuntimeException("Business API error: " + e.getMessage());
        }
    }

    public String post(String path, Object payload) {
        String token = acquireToken();
        String url = businessBaseUrl + path + "?access_token=" + token;
        log.info("[BusinessApiClient] POST {}", url);
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpURLConnection conn = openConnection(url, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            log.debug("[BusinessApiClient] POST {} → {} {}", path, status, body);
            return body;
        } catch (Exception e) {
            log.error("[BusinessApiClient] POST {} failed: {}", path, e.getMessage());
            throw new RuntimeException("Business API error: " + e.getMessage());
        }
    }

    public String acquireToken() {
        String url = idpBaseUrl + "/auth/realms/PriceSmart/protocol/openid-connect/token";
        try {
            String body = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;
            HttpURLConnection conn = openConnection(url, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = objectMapper.readValue(response, Map.class);
            return resp.getOrDefault("access_token", "").toString();
        } catch (Exception e) {
            log.error("[BusinessApiClient] Token acquisition failed: {}", e.getMessage());
            throw new RuntimeException("Error adquiriendo token: " + e.getMessage());
        }
    }

    private HttpURLConnection openConnection(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        return conn;
    }

    private void disableSslVerification() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            log.warn("[BusinessApiClient] Could not disable SSL verification: {}", e.getMessage());
        }
    }
}
