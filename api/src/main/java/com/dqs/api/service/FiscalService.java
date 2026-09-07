package com.dqs.api.service;

import com.dqs.api.client.GoSocketClient;
import com.dqs.api.model.City;
import com.dqs.api.model.EconomicActivity;
import com.dqs.api.model.Neighborhood;
import com.dqs.api.model.QuotationFiscal;
import com.dqs.api.model.Zone;
import com.dqs.api.repository.CityRepository;
import com.dqs.api.repository.EconomicActivityRepository;
import com.dqs.api.repository.NeighborhoodRepository;
import com.dqs.api.repository.QuotationFiscalRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.ZoneRepository;
import com.dqs.api.repository.support.NativeQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fiscal / electronic-invoicing data for a quotation.
 *
 * `quotation_fiscal` and the four catalogs (cities, zones, neighborhoods,
 * economic_activities) are ours and go through JPA. `ps_fel` — the document
 * type catalog — belongs to the legacy application and is read with a native
 * query rather than mapped, so a schema change on their side cannot stop this
 * one from starting. See repository/CLAUDE.md.
 *
 * Maps in and maps out, with the snake_case keys the frontend reads
 * (src/api/fiscal.ts). Moving to JPA changed persistence, not the contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FiscalService {

    private final GoSocketClient goSocketClient;
    private final ObjectMapper objectMapper;
    private final NativeQueries nativeQueries;

    private final QuotationFiscalRepository fiscalRepository;
    private final QuotationRepository quotationRepository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final EconomicActivityRepository economicActivityRepository;

    @Value("${gosocket.account-code}")
    private String accountCode;

    @Value("${gosocket.country-id}")
    private String countryId;

    @Value("${gosocket.identification-type}")
    private String defaultIdentificationType;

    // ── GoSocket validation ───────────────────────────────────────────────────

    public Map<String, Object> validateIdentification(String receiverCode, String identificationType) {
        log.info("[FiscalService] validateIdentification code={} type={}", receiverCode, identificationType);
        String resolvedType = (identificationType != null && !identificationType.isBlank())
                ? identificationType : defaultIdentificationType;
        return goSocketClient.getAccount(accountCode, resolvedType, receiverCode, countryId);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Transactional
    public boolean saveFiscalData(Map<String, Object> data) {
        Long quotationId  = toLong(data.get("quotation_id"));
        String membership = str(data.get("membership"));
        log.info("[FiscalService] saveFiscalData quotationId={} membership={}", quotationId, membership);

        QuotationFiscal fiscal = fiscalRepository.findByQuotation_Id(quotationId)
            .orElseGet(() -> QuotationFiscal.builder()
                .quotation(quotationRepository.getReferenceById(quotationId))
                .build());

        fiscal.setMembership(membership);
        fiscal.setCountry(str(data.get("country")));
        fiscal.setDocumentType(str(data.get("document_type")));
        fiscal.setDocumentNumber(str(data.get("document_number")));
        fiscal.setDocumentValidated(toBool(data.get("document_validated")));
        fiscal.setBusinessName(str(data.get("business_name")));
        fiscal.setAddress(str(data.get("address")));
        fiscal.setPhone(str(data.get("phone")));
        fiscal.setEmail(str(data.get("email")));
        fiscal.setNrc(str(data.get("nrc")));
        fiscal.setEconomicActivityCode(str(data.get("economic_activity_code")));
        fiscal.setCityCode(str(data.get("city_code")));
        fiscal.setZoneCode(str(data.get("zone_code")));
        fiscal.setNeighborhoodCode(str(data.get("neighborhood_code")));

        fiscalRepository.save(fiscal);
        upsertCatalogs(data);
        return true;
    }

    /**
     * Remember any catalog entry the provider sent that we had not seen.
     *
     * These four tables are a cache of what GoSocket returns, not a governed
     * list, which is why the codes are plain columns on quotation_fiscal rather
     * than foreign keys: a code arriving for the first time has to save, not
     * fail.
     */
    private void upsertCatalogs(Map<String, Object> data) {
        String country = str(data.get("country"));

        String eaCode = str(data.get("economic_activity_code"));
        String eaName = str(data.get("economic_activity_name"));
        if (eaCode != null && eaName != null) {
            // `code` carries a unique index of its own here, narrower than the
            // (code, country) pair the other three use.
            EconomicActivity ea = economicActivityRepository.findByCode(eaCode)
                .orElseGet(() -> EconomicActivity.builder().code(eaCode).country(country).build());
            ea.setValue(eaName);
            economicActivityRepository.save(ea);
        }

        String cityCode = str(data.get("city_code"));
        String cityName = str(data.get("city_name"));
        if (cityCode != null && cityName != null) {
            City city = cityRepository.findByCodeAndCountry(cityCode, country)
                .orElseGet(() -> City.builder().code(cityCode).country(country).build());
            city.setName(cityName);
            cityRepository.save(city);
        }

        String zoneCode = str(data.get("zone_code"));
        String zoneName = str(data.get("zone_name"));
        if (zoneCode != null && zoneName != null) {
            Zone zone = zoneRepository.findByCodeAndCityCode(zoneCode, cityCode)
                .orElseGet(() -> Zone.builder().code(zoneCode).cityCode(cityCode).build());
            zone.setName(zoneName);
            zoneRepository.save(zone);
        }

        String nbCode = str(data.get("neighborhood_code"));
        String nbName = str(data.get("neighborhood_name"));
        if (nbCode != null && nbName != null) {
            Neighborhood nb = neighborhoodRepository
                .findByCodeAndZoneCodeAndCityCode(nbCode, zoneCode, cityCode)
                .orElseGet(() -> Neighborhood.builder()
                    .code(nbCode).zoneCode(zoneCode).cityCode(cityCode).build());
            nb.setName(nbName);
            neighborhoodRepository.save(nb);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * The saved record plus the display name of each catalog code, which is
     * what the OMS payload and the fiscal form both need.
     *
     * The old single query LEFT JOINed the four catalogs. This resolves them
     * one lookup at a time: each is an indexed hit on a table of at most a few
     * hundred rows, and a missing name now reads as a missing name rather than
     * as a join condition that silently did not match.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getFiscalDataByQuotation(Long quotationId) {
        log.info("[FiscalService] getFiscalDataByQuotation quotationId={}", quotationId);
        return fiscalRepository.findByQuotation_Id(quotationId).map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",                     f.getId());
            m.put("quotation_id",           f.getQuotation() != null ? f.getQuotation().getId() : null);
            m.put("membership",             f.getMembership());
            m.put("country",                f.getCountry());
            m.put("document_type",          f.getDocumentType());
            m.put("document_number",        f.getDocumentNumber());
            m.put("document_validated",     Boolean.TRUE.equals(f.getDocumentValidated()) ? 1 : 0);
            m.put("business_name",          f.getBusinessName());
            m.put("address",                f.getAddress());
            m.put("phone",                  f.getPhone());
            m.put("email",                  f.getEmail());
            m.put("nrc",                    f.getNrc());
            m.put("economic_activity_code", f.getEconomicActivityCode());
            m.put("city_code",              f.getCityCode());
            m.put("zone_code",              f.getZoneCode());
            m.put("neighborhood_code",      f.getNeighborhoodCode());
            m.put("created_at",             f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
            m.put("updated_at",             f.getUpdatedAt() != null ? f.getUpdatedAt().toString() : null);

            m.put("economic_activity_name", f.getEconomicActivityCode() == null ? null
                : economicActivityRepository.findByCode(f.getEconomicActivityCode())
                    .map(EconomicActivity::getValue).orElse(null));
            m.put("city_name", f.getCityCode() == null ? null
                : cityRepository.findByCodeAndCountry(f.getCityCode(), f.getCountry())
                    .map(City::getName).orElse(null));
            m.put("zone_name", f.getZoneCode() == null ? null
                : zoneRepository.findByCodeAndCityCode(f.getZoneCode(), f.getCityCode())
                    .map(Zone::getName).orElse(null));
            m.put("neighborhood_name", f.getNeighborhoodCode() == null ? null
                : neighborhoodRepository.findByCodeAndZoneCodeAndCityCode(
                        f.getNeighborhoodCode(), f.getZoneCode(), f.getCityCode())
                    .map(Neighborhood::getName).orElse(null));
            return m;
        }).orElse(null);
    }

    // ── Catalogs ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getEconomicActivities(String country) {
        return economicActivityRepository.findByCountryOrderByValue(country).stream()
            .map(a -> codeValue(a.getCode(), "value", a.getValue()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCities(String country) {
        return cityRepository.findByCountryOrderByName(country).stream()
            .map(c -> codeValue(c.getCode(), "name", c.getName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getZones(String cityCode) {
        return zoneRepository.findByCityCodeOrderByName(cityCode).stream()
            .map(z -> codeValue(z.getCode(), "name", z.getName()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getNeighborhoods(String zoneCode, String cityCode) {
        return neighborhoodRepository.findByZoneCodeAndCityCodeOrderByName(zoneCode, cityCode).stream()
            .map(n -> codeValue(n.getCode(), "name", n.getName()))
            .toList();
    }

    /**
     * Document types for a country — legacy's `ps_fel`, read natively.
     *
     * nombre_en is the document's type code — NIT, CUI, PHYSICAL, LEGAL, DIMEX,
     * NITE — which is what the per-type number validation keys off. Legacy
     * carries it on the option as data-type; without it the frontend can only
     * match on the Spanish label, which is a display string and free to change.
     */
    public List<Map<String, Object>> getDocTypes(String country) {
        return nativeQueries.list(
            "SELECT felid, nombre_es AS descripcion, nombre_en AS typeCode, formato " +
            "FROM ps_fel WHERE pais_iso2 = ?1 ORDER BY nombre_es",
            country);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Catalog rows have always gone out as {code, name} or {code, value}. */
    private Map<String, Object> codeValue(String code, String labelKey, String label) {
        Map<String, Object> m = new LinkedHashMap<>(2);
        m.put("code", code);
        m.put(labelKey, label);
        return m;
    }

    private String str(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    /** The client sends 0/1; older callers and JSON booleans send true/false. */
    private Boolean toBool(Object v) {
        if (v == null) return Boolean.FALSE;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}
