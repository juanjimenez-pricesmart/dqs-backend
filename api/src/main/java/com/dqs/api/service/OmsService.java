// service/OmsService.java
package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;


@Slf4j
@Service
@RequiredArgsConstructor
public class OmsService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${oms.base-url}")
    private String omsBaseUrl;

    @Value("${oms.timeout:30000}")
    private int timeout;

    @Value("${business.api.membership-url:https://business-stage.api.int.pricesmart.com}")
    private String businessApiUrl;

    // ── Contexto desde DB directo ─────────────────────────────────────────

    public Map<String, Object> getQuotationContext(Long quotationId, Integer storeId, String membership, Integer userId) {

        log.info("[OmsService] getQuotationContext quotation_id={} store_id={} membership={}",
                quotationId, storeId, membership);

        // Club
        List<Map<String, Object>> clubRows = jdbcTemplate.queryForList(
            "SELECT ps_tienda_id, nombre, moneda, idioma, pais_iso2, impuesto_operacion " +
            "FROM ps_tienda WHERE ps_tienda_id = ?", storeId);

        Map<String, Object> club = clubRows.isEmpty() ? new HashMap<>() : clubRows.get(0);

        // Tasa de cambio — la más reciente
        String paisIso2 = (String) club.getOrDefault("pais_iso2", "CR");
        List<Map<String, Object>> tasaRows = jdbcTemplate.queryForList(
            "SELECT ps_tasa_cambio_tipocambio FROM ps_tasa_cambio " +
            "WHERE ps_pais_iso2 = ? ORDER BY ps_tasa_cambio_id DESC LIMIT 1", paisIso2);

        double tasaCambio = tasaRows.isEmpty() ? 1.0
            : ((Number) tasaRows.get(0).get("ps_tasa_cambio_tipocambio")).doubleValue();

        // Usuario
        List<Map<String, Object>> userRows = jdbcTemplate.queryForList(
            "SELECT id, email FROM users WHERE id = ?", userId);

        Map<String, Object> usuario = userRows.isEmpty()
            ? Map.of("id", userId, "email", "")
            : userRows.get(0);

        // Socio — API de membresía
        Map<String, Object> socio = getMembershipData(membership, quotationId);

        // FEL — solo para Guatemala (6300-6399), para esta iteración retorna null
        Map<String, Object> fel = null;
        if (storeId >= 6300 && storeId <= 6399) {
            List<Map<String, Object>> felRows = jdbcTemplate.queryForList(
                "SELECT psf.nit, psf.businessname, psf.address, psf.phone, psf.email, " +
                "psf.nit_validated, pf.nombre_en as doc_type_name " +
                "FROM ps_socios_fel psf " +
                "LEFT JOIN ps_fel pf ON psf.felid = pf.felid " +
                "WHERE psf.membership = ? AND psf.cotizacion = ?",
                membership, quotationId);

            if (!felRows.isEmpty()) {
                fel = felRows.get(0);
            }
        }

        Map<String, Object> context = new HashMap<>();
        context.put("club",        club);
        context.put("tasa_cambio", tasaCambio);
        context.put("usuario",     usuario);
        context.put("socio",       socio);
        context.put("fel",         fel);
        context.put("delivery",    null);

        log.info("[OmsService] context built club={} tasa={} socio_email={}",
            club.get("nombre"), tasaCambio, socio.get("email"));

        return context;
    }

    // ── API de membresía ──────────────────────────────────────────────────

    private Map<String, Object> getMembershipData(String membership, Long quotationId) {
        String url = businessApiUrl + "/api/members/" + membership;
        log.info("[OmsService] getMembershipData url={}", url);

        try {
            // Deshabilitar verificación SSL (solo para desarrollo local)
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            log.info("[OmsService] membership API status={} body={}", status, body);

            if (status == 200) {
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                overrideWithLocalSocio(membership, resp);
                return resp;
            }

        } catch (Exception e) {
            log.error("[OmsService] Error calling membership API: {}", e.getMessage());
        }

        // Fallback
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("firstName",    "N/A");
        fallback.put("lastName",     "N/A");
        fallback.put("email",        "noreply@pricesmart.com");
        fallback.put("cellPhone",    "0000000000");
        fallback.put("addressLine1", "N/A");
        fallback.put("city",         "N/A");
        fallback.put("countryCode",  "CR");
        fallback.put("businessName", "N/A");
        return fallback;
    }

    private void overrideWithLocalSocio(String membership, Map<String, Object> socio) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT addressLine1, cellPhone, email, businessName " +
                "FROM ps_socios WHERE membership = ?", membership);

            if (!rows.isEmpty()) {
                Map<String, Object> local = rows.get(0);
                if (local.get("addressLine1") != null) socio.put("addressLine1", local.get("addressLine1"));
                if (local.get("cellPhone")    != null) socio.put("cellPhone",    local.get("cellPhone"));
                if (local.get("email")        != null) socio.put("email",        local.get("email"));
                if (local.get("businessName") != null) socio.put("businessName", local.get("businessName"));
            }
        } catch (Exception e) {
            log.warn("[OmsService] Could not override with local socio: {}", e.getMessage());
        }
    }

    // ── Historial de status OMS ───────────────────────────────────────────

    public String getOrderStatusHistory(String quoteNo, String token) {
        String url = omsBaseUrl + "/api/status/history/" + quoteNo + "?access_token=" + token;
        log.info("[OmsService] getOrderStatusHistory url={}", url);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            java.io.InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            log.info("[OmsService] getOrderStatusHistory status={}", status);
            return response;
        } catch (Exception e) {
            log.error("[OmsService] Error getting OMS status history: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Envío a OMS ───────────────────────────────────────────────────────

    public String sendPayload(Map<String, Object> payload, String paisIso2, String token) {
        String url = omsBaseUrl + "/api/orders?access_token=" + token;
        String organization = "PriceSmart" + paisIso2;

        log.info("[OmsService] sendPayload url={} org={}", url, organization);

        try {
            String json = objectMapper.writeValueAsString(payload);
            log.info("[OmsService] payload={}", json);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Organization", organization);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            log.info("[OmsService] OMS response status={} body={}", status, response);
            conn.disconnect();
            return response;

        } catch (Exception e) {
            log.error("[OmsService] Error sending to OMS: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}