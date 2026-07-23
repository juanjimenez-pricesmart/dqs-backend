package com.dqs.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceSmartPaymentService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${pricesmart.payments.base-url}")
    private String paymentsBaseUrl;

    @Value("${pricesmart.payments.api-url}")
    private String paymentsApiUrl;

    @Value("${pricesmart.payments.idp-token-url}")
    private String paymentsIdpTokenUrl;

    @Value("${pricesmart.payments.callback-url}")
    private String callbackUrl;

    @Value("${pricesmart.payments.client-id}")
    private String clientId;

    @Value("${pricesmart.payments.client-secret}")
    private String clientSecret;

    @Value("${pricesmart.payments.return-url}")
    private String returnUrl;

    // ── Create Payment Request ────────────────────────────────────────────────

    public Map<String, Object> createPaymentRequest(Long quotationId, String ipAddress) {
        log.info("[PriceSmartPaymentService] createPaymentRequest quotationId={}", quotationId);

        // Load quotation + customer + store
        List<Map<String, Object>> quoteRows = jdbcTemplate.queryForList(
            "SELECT q.id, qc.customer_membership, qc.customer_name, q.store_id, " +
            "       t.pais_iso2, t.nombre as store_nombre " +
            "FROM quotations q " +
            "LEFT JOIN quotation_customers qc ON qc.quotation_id = q.id " +
            "JOIN ps_tienda t ON t.ps_tienda_id = q.store_id " +
            "WHERE q.id = ?", quotationId);

        if (quoteRows.isEmpty()) {
            throw new IllegalArgumentException("Quotation not found: " + quotationId);
        }
        Map<String, Object> quote = quoteRows.get(0);

        String membership    = (String) quote.get("customer_membership");
        String customerName  = (String) quote.get("customer_name");
        String countryIso2   = ((String) quote.getOrDefault("pais_iso2", "CR")).toUpperCase();
        Integer storeId      = ((Number) quote.get("store_id")).intValue();

        // Load items (description and category live in quotation_item_product)
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
            "SELECT qi.product_id, qip.description, qi.qty, qi.rate, qip.category " +
            "FROM quotation_items qi " +
            "LEFT JOIN quotation_item_product qip ON qip.item_id = qi.id " +
            "WHERE qi.quotation_id = ?", quotationId);

        double totalAmount = items.stream()
            .mapToDouble(i -> ((Number) i.get("qty")).doubleValue() * ((Number) i.get("rate")).doubleValue())
            .sum();

        // Resolve member email
        String email = resolveEmail(membership, customerName, quotationId);

        // Build shoppingCart — category field must be the item description (per payment API spec)
        List<Map<String, Object>> cart = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> cartItem = new HashMap<>();
            cartItem.put("itemId",   String.valueOf(item.get("product_id")));
            cartItem.put("category", item.getOrDefault("description", "General"));
            cartItem.put("quantity", ((Number) item.get("qty")).doubleValue());
            cartItem.put("price",    ((Number) item.get("rate")).doubleValue());
            cart.add(cartItem);
        }

        // Build numeric invoiceId matching the format the payment API expects:
        // 8-digit zero-padded quoteId + 2-digit zero-padded sequential attempt number
        // e.g. quotation 52, attempt 1 → "0000005201"
        int attempt = getNextAttemptNumber(quotationId);
        String invoiceId = String.format("%08d%02d", quotationId, attempt);

        Map<String, Object> detail = new HashMap<>();
        detail.put("invoiceId",    invoiceId);
        detail.put("amount",       totalAmount > 0 ? totalAmount : 1.0);
        detail.put("shoppingCart", cart);

        // Split customer name into first / last for shipping
        String[] nameParts  = customerName != null ? customerName.trim().split("\\s+", 2) : new String[]{"N/A", "N/A"};
        String firstName    = nameParts[0];
        String lastName     = nameParts.length > 1 ? nameParts[1] : nameParts[0];

        String storeName = (String) quote.getOrDefault("store_nombre", "Cartago");

        Map<String, Object> shipping = new HashMap<>();
        shipping.put("firstName",     firstName);
        shipping.put("lastName",      lastName);
        shipping.put("address",       "N/A");
        shipping.put("city",          storeName);
        shipping.put("country",       countryIso2);
        shipping.put("deliverySpeed", "PICK_UP");

        Map<String, Object> body = new HashMap<>();
        body.put("country",              countryIso2);
        body.put("club",                 String.valueOf(storeId));
        body.put("clientKey",            membership != null ? membership : "");
        body.put("delivery",             "PICK_UP");
        body.put("address",              "N/A");
        body.put("channel",              "ECOM");
        body.put("email",                email);
        body.put("callBackUrl",          callbackUrl);
        body.put("returnUrl",            returnUrl);
        body.put("paymentRequestType",   "payment_link");
        body.put("ecommerce",            "PriceSmart");
        body.put("process",              "ORDER_PLACEMENT");
        body.put("localTotalAmount",     totalAmount > 0 ? totalAmount : 1.0);
        body.put("ipAddress",            ipAddress != null ? ipAddress : "0.0.0.0");
        body.put("shipping",             shipping);
        body.put("language",             "en");
        body.put("paymentRequestDetail", List.of(detail));

        String accessToken = acquirePaymentsToken();
        try { log.info("[PriceSmartPaymentService] createPaymentRequest payload={}", objectMapper.writeValueAsString(body)); } catch (Exception ignored) {}
        String response    = post(paymentsApiUrl + "/api/paymentRequest/create", body, accessToken);

        log.info("[PriceSmartPaymentService] createPaymentRequest response={}", response);

        Map<String, Object> result = parseJson(response);

        // Enrich with the iframe embed URL so frontend can use it directly
        Object authToken = result.get("authorizationToken");
        if (authToken != null) {
            result.put("iframeUrl", paymentsBaseUrl + "/?authorization_token=" + authToken);
        }

        // Always surface the invoiceId we sent so the frontend can use it for status checks
        result.putIfAbsent("invoice", invoiceId);

        // Persist the attempt so we can correlate invoice → quotation later
        jdbcTemplate.update(
            "INSERT INTO quotation_payment_attempts (quotation_id, invoice_id, authorization_token) VALUES (?,?,?) " +
            "ON DUPLICATE KEY UPDATE authorization_token = VALUES(authorization_token)",
            quotationId, invoiceId, authToken != null ? authToken.toString() : null);

        return result;
    }

    // ── Status check ─────────────────────────────────────────────────────────

    public Map<String, Object> getPaymentStatus(String invoiceId) {
        log.info("[PriceSmartPaymentService] getPaymentStatus invoiceId={}", invoiceId);
        String accessToken = acquirePaymentsToken();
        String url = paymentsApiUrl + "/api/wallet/get-status?invoiceId=" + invoiceId;
        try {
            HttpURLConnection conn = openGet(url, accessToken);
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            log.info("[PriceSmartPaymentService] getPaymentStatus status={} body={}", status, body);
            return parseJson(body);
        } catch (Exception e) {
            log.error("[PriceSmartPaymentService] getPaymentStatus error: {}", e.getMessage());
            throw new RuntimeException("Error checking payment status: " + e.getMessage());
        }
    }

    // ── IDP token (pricesmart realm) ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String acquirePaymentsToken() {
        try {
            String form = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;
            HttpURLConnection conn = (HttpURLConnection) new URL(paymentsIdpTokenUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            log.info("[PriceSmartPaymentService] IDP token status={}", status);
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            String token = (String) resp.get("access_token");
            if (token == null) throw new RuntimeException("IDP did not return access_token: " + body);
            return token;
        } catch (Exception e) {
            log.error("[PriceSmartPaymentService] IDP token error: {}", e.getMessage());
            throw new RuntimeException("Could not obtain payments IDP token: " + e.getMessage());
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String post(String url, Map<String, Object> body, String token) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            return response;
        } catch (Exception e) {
            throw new RuntimeException("POST " + url + " failed: " + e.getMessage());
        }
    }

    private HttpURLConnection openGet(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    /** Returns the next sequential attempt number (1-based) for a quotation. */
    private int getNextAttemptNumber(Long quotationId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM quotation_payment_attempts WHERE quotation_id = ?",
            Integer.class, quotationId);
        return (count != null ? count : 0) + 1;
    }

    private String resolveEmail(String membership, String customerName, Long quotationId) {
        if (membership != null && !membership.isBlank()) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT email FROM ps_socios WHERE membership = ? LIMIT 1", membership);
                if (!rows.isEmpty() && rows.get(0).get("email") != null) {
                    return rows.get(0).get("email").toString();
                }
            } catch (Exception ignored) {}
        }
        return "quote-" + quotationId + "@pricesmart.com";
    }
}
