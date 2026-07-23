package com.dqs.api.service;

import com.dqs.api.client.GoSocketClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FiscalService {

    private final GoSocketClient goSocketClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gosocket.account-code}")
    private String accountCode;

    @Value("${gosocket.country-id}")
    private String countryId;

    @Value("${gosocket.identification-type}")
    private String defaultIdentificationType;

    // ── GoSocket Validation ───────────────────────────────────────────────────

    public Map<String, Object> validateIdentification(String receiverCode, String identificationType) {
        log.info("[FiscalService] validateIdentification code={} type={}", receiverCode, identificationType);
        String resolvedType = (identificationType != null && !identificationType.isBlank())
                ? identificationType : defaultIdentificationType;
        return goSocketClient.getAccount(accountCode, resolvedType, receiverCode, countryId);
    }

    // ── Save Fiscal Data ──────────────────────────────────────────────────────

    public boolean saveFiscalData(Map<String, Object> data) {
        Object quotationId = data.get("quotation_id");
        String membership  = (String) data.get("membership");
        log.info("[FiscalService] saveFiscalData quotationId={} membership={}", quotationId, membership);

        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quotation_fiscal WHERE quotation_id = ?",
                Integer.class, quotationId);

            if (count != null && count > 0) {
                jdbcTemplate.update(
                    "UPDATE quotation_fiscal SET " +
                    "  membership = ?, country = ?, document_type = ?, document_number = ?, document_validated = ?, " +
                    "  business_name = ?, address = ?, phone = ?, email = ?, nrc = ?, " +
                    "  economic_activity_code = ?, city_code = ?, zone_code = ?, neighborhood_code = ? " +
                    "WHERE quotation_id = ?",
                    membership,
                    data.get("country"),
                    data.get("document_type"),
                    data.get("document_number"),
                    data.getOrDefault("document_validated", 0),
                    data.get("business_name"),
                    data.get("address"),
                    data.get("phone"),
                    data.get("email"),
                    data.get("nrc"),
                    data.get("economic_activity_code"),
                    data.get("city_code"),
                    data.get("zone_code"),
                    data.get("neighborhood_code"),
                    quotationId);
            } else {
                jdbcTemplate.update(
                    "INSERT INTO quotation_fiscal " +
                    "  (quotation_id, membership, country, document_type, document_number, document_validated, " +
                    "   business_name, address, phone, email, nrc, " +
                    "   economic_activity_code, city_code, zone_code, neighborhood_code) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    quotationId,
                    membership,
                    data.get("country"),
                    data.get("document_type"),
                    data.get("document_number"),
                    data.getOrDefault("document_validated", 0),
                    data.get("business_name"),
                    data.get("address"),
                    data.get("phone"),
                    data.get("email"),
                    data.get("nrc"),
                    data.get("economic_activity_code"),
                    data.get("city_code"),
                    data.get("zone_code"),
                    data.get("neighborhood_code"));
            }

            upsertCatalogs(data);
            return true;

        } catch (Exception e) {
            log.error("[FiscalService] Error saving fiscal data: {}", e.getMessage());
            return false;
        }
    }

    private void upsertCatalogs(Map<String, Object> data) {
        String country = (String) data.get("country");

        String eaCode = (String) data.get("economic_activity_code");
        String eaName = (String) data.get("economic_activity_name");
        if (isPresent(eaCode) && isPresent(eaName)) {
            jdbcTemplate.update(
                "INSERT INTO economic_activities (code, value, country) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE value = VALUES(value)",
                eaCode, eaName, country);
        }

        String cityCode = (String) data.get("city_code");
        String cityName = (String) data.get("city_name");
        if (isPresent(cityCode) && isPresent(cityName)) {
            jdbcTemplate.update(
                "INSERT INTO cities (code, name, country) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)",
                cityCode, cityName, country);
        }

        String zoneCode = (String) data.get("zone_code");
        String zoneName = (String) data.get("zone_name");
        if (isPresent(zoneCode) && isPresent(zoneName)) {
            jdbcTemplate.update(
                "INSERT INTO zones (code, name, city_code) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)",
                zoneCode, zoneName, cityCode);
        }

        String neighborhoodCode = (String) data.get("neighborhood_code");
        String neighborhoodName = (String) data.get("neighborhood_name");
        if (isPresent(neighborhoodCode) && isPresent(neighborhoodName)) {
            jdbcTemplate.update(
                "INSERT INTO neighborhoods (code, name, zone_code, city_code) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)",
                neighborhoodCode, neighborhoodName, zoneCode, cityCode);
        }
    }

    private boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    // ── Get Saved Fiscal Data by Quotation (for OMS) ─────────────────────────

    public Map<String, Object> getFiscalDataByQuotation(Long quotationId) {
        log.info("[FiscalService] getFiscalDataByQuotation quotationId={}", quotationId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT qf.*, " +
            "  ea.value  AS economic_activity_name, " +
            "  c.name    AS city_name, " +
            "  z.name    AS zone_name, " +
            "  n.name    AS neighborhood_name " +
            "FROM quotation_fiscal qf " +
            "LEFT JOIN economic_activities ea ON ea.code = qf.economic_activity_code AND ea.country = qf.country " +
            "LEFT JOIN cities c              ON c.code  = qf.city_code              AND c.country  = qf.country " +
            "LEFT JOIN zones z               ON z.code  = qf.zone_code              AND z.city_code = qf.city_code " +
            "LEFT JOIN neighborhoods n       ON n.code  = qf.neighborhood_code      AND n.zone_code = qf.zone_code AND n.city_code = qf.city_code " +
            "WHERE qf.quotation_id = ?",
            quotationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Catalog Queries ───────────────────────────────────────────────────────

    public List<Map<String, Object>> getEconomicActivities(String country) {
        return jdbcTemplate.queryForList(
            "SELECT code, value FROM economic_activities WHERE country = ? ORDER BY value",
            country);
    }

    public List<Map<String, Object>> getCities(String country) {
        return jdbcTemplate.queryForList(
            "SELECT code, name FROM cities WHERE country = ? ORDER BY name",
            country);
    }

    public List<Map<String, Object>> getZones(String cityCode) {
        return jdbcTemplate.queryForList(
            "SELECT code, name FROM zones WHERE city_code = ? ORDER BY name",
            cityCode);
    }

    public List<Map<String, Object>> getNeighborhoods(String zoneCode, String cityCode) {
        return jdbcTemplate.queryForList(
            "SELECT code, name FROM neighborhoods WHERE zone_code = ? AND city_code = ? ORDER BY name",
            zoneCode, cityCode);
    }

    public List<Map<String, Object>> getDocTypes(String country) {
        return jdbcTemplate.queryForList(
            "SELECT felid, nombre_es AS descripcion, formato FROM ps_fel WHERE pais_iso2 = ? ORDER BY nombre_es",
            country);
    }
}
