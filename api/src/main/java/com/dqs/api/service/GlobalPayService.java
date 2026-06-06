package com.dqs.api.service;

import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalPayService {

    private final JdbcTemplate jdbcTemplate;
    private final QuotationRepository quotationRepository;

    /**
     * Generates the GlobalPay auth token for the frontend payment widget.
     * Token = Base64( appCode + ";" + timestamp + ";" + SHA256(appKey + timestamp) )
     */
    public Map<String, Object> getPaymentToken(Integer storeId) {
        log.info("[GlobalPayService] getPaymentToken storeId={}", storeId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT server_appcode, server_appkey FROM ps_globalpay_credenciales WHERE store_id = ? LIMIT 1",
                storeId);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No se encontraron credenciales GlobalPay para storeId=" + storeId);
        }

        String appCode = rows.get(0).get("server_appcode").toString();
        String appKey  = rows.get(0).get("server_appkey").toString();

        long timestamp = System.currentTimeMillis() / 1000;
        String hash  = sha256Hex(appKey + timestamp);
        String token = Base64.getEncoder().encodeToString(
                (appCode + ";" + timestamp + ";" + hash).getBytes(StandardCharsets.UTF_8));

        log.info("[GlobalPayService] token generated for storeId={}", storeId);
        return Map.of("token", token, "storeId", storeId);
    }

    /**
     * Processes GlobalPay callback. Updates quotation paid status.
     * Expected body: { "transaction": { "dev_reference": "quotationId", "status": 4, ... } }
     */
    @Transactional
    public void processCallback(Map<String, Object> body) {
        log.info("[GlobalPayService] processCallback body={}", body);

        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) body.get("transaction");
        if (transaction == null) {
            log.warn("[GlobalPayService] callback missing 'transaction' field");
            return;
        }

        String devReference = transaction.get("dev_reference") != null
                ? transaction.get("dev_reference").toString() : null;
        Object statusObj = transaction.get("status");

        if (devReference == null || statusObj == null) {
            log.warn("[GlobalPayService] callback missing dev_reference or status");
            return;
        }

        Long quotationId;
        try {
            quotationId = Long.parseLong(devReference);
        } catch (NumberFormatException e) {
            log.warn("[GlobalPayService] dev_reference is not a valid quotation id: {}", devReference);
            return;
        }

        int gpStatus = Integer.parseInt(statusObj.toString());
        // GlobalPay: 4 = paid, 3 = failed/rejected
        int paidStatus = gpStatus == 4 ? 4 : 3;

        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        if (quotation.getPayment() != null) {
            quotation.getPayment().setPaidStatus(paidStatus);
            quotationRepository.save(quotation);
            log.info("[GlobalPayService] updated quotationId={} paidStatus={}", quotationId, paidStatus);
        } else {
            log.warn("[GlobalPayService] quotation {} has no payment record", quotationId);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error: " + e.getMessage());
        }
    }
}
