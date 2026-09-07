package com.dqs.api.client;

import com.dqs.api.exception.BusinessApiException;
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
        String url = buildUrl(path, token);
        // Never log `url`: the access token is a query parameter on it.
        log.info("[BusinessApiClient] GET {}", path);
        try {
            HttpURLConnection conn = openConnection(url, "GET");
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            String body = readBody(conn, status);
            conn.disconnect();
            log.debug("[BusinessApiClient] GET {} → {} {}", path, status, body);
            if (status >= 400) {
                throw new BusinessApiException(status, path, body);
            }
            return body;
        } catch (BusinessApiException e) {
            log.warn("[BusinessApiClient] GET {} → {}", path, e.getStatus());
            throw e;
        } catch (Exception e) {
            log.error("[BusinessApiClient] GET {} failed: {}", path, e.getMessage());
            throw new RuntimeException("Business API error: " + e.getMessage());
        }
    }

    public String post(String path, Object payload) {
        String token = acquireToken();
        String url = buildUrl(path, token);
        // Never log `url`: the access token is a query parameter on it.
        log.info("[BusinessApiClient] POST {}", path);
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
            String body = readBody(conn, status);
            conn.disconnect();
            log.debug("[BusinessApiClient] POST {} → {} {}", path, status, body);
            if (status >= 400) {
                throw new BusinessApiException(status, path, body);
            }
            return body;
        } catch (BusinessApiException e) {
            log.warn("[BusinessApiClient] POST {} → {}", path, e.getStatus());
            throw e;
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

    /**
     * Joins the configured base URL with a caller path, tolerating either form
     * of base URL.
     *
     * Every caller writes paths the way legacy DQS does — `/api/membership/...`,
     * `/api/getItemCode/...` — because legacy appends exactly that to its
     * BUSINESS host, which carries no path. But MEMBERSHIP_BASE_URL is
     * configured *with* the `/api` suffix already on it, so naive concatenation
     * produced `/api/api/membership/validate/...` and every lookup 404'd.
     * Normalising here fixes it for whichever form an environment is set to,
     * instead of depending on each developer's .env.local being written a
     * particular way — .env.local is not in the repository.
     */
    String buildUrl(String path, String token) {
        String base = businessBaseUrl == null ? "" : businessBaseUrl.replaceAll("/+$", "");
        String suffix = path == null ? "" : path;
        if (base.endsWith("/api") && suffix.startsWith("/api/")) {
            suffix = suffix.substring("/api".length());
        }
        return base + suffix + "?access_token=" + token;
    }

    /**
     * Reads the response body. getErrorStream() returns null for some error
     * responses, which previously threw a NullPointerException — surfacing an
     * upstream 404 as "Internal server error" and hiding the real cause.
     */
    private String readBody(HttpURLConnection conn, int status) throws Exception {
        InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
