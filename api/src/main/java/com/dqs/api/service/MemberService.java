package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;
import com.dqs.api.model.MemberContactOverride;
import com.dqs.api.repository.MemberContactOverrideRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dqs.api.repository.support.NativeQueries;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;
    private final NativeQueries nativeQueries;
    private final MemberContactOverrideRepository overrideRepository;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMember(String membership) {
        log.info("[MemberService] getMember membership={}", membership);
        String response = businessApiClient.get("/api/membership/validate/" + membership);
        Map<String, Object> member;
        try {
            member = objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[MemberService] Error parsing member response: {}", e.getMessage());
            throw new RuntimeException("Error consultando membresía: " + e.getMessage());
        }
        return applyContactOverride(membership, member);
    }

    /**
     * Overlays the staff correction on top of the Business API answer, which is
     * exactly what Orders::buscarmembresia does after reading ps_socios
     * (Orders.php:1516-1521): when a local row exists, its four fields win.
     *
     * The four keys are the API's own — addressLine1, cellPhone, email,
     * businessName — so the caller cannot tell a corrected field from an
     * original one, and nothing downstream needs to know about overrides.
     *
     * A stored empty string overwrites too, and deliberately: the operator who
     * clears a wrong phone number expects it to stay cleared. Only a column
     * that was never written (null) leaves the API value alone.
     */
    private Map<String, Object> applyContactOverride(String membership, Map<String, Object> member) {
        if (member == null) return null;
        overrideRepository.findByMembershipNumber(membership).ifPresent(o -> {
            if (o.getAddressLine1() != null) member.put("addressLine1", o.getAddressLine1());
            if (o.getPhone()        != null) member.put("cellPhone",    o.getPhone());
            if (o.getEmail()        != null) member.put("email",        o.getEmail());
            if (o.getBusinessName() != null) member.put("businessName", o.getBusinessName());
            log.info("[MemberService] contact override applied membership={}", membership);
        });
        return member;
    }

    /**
     * Upserts the staff correction — legacy's orders/savedatamembership, which
     * writes ps_socios. We write our own table instead: ps_socios belongs to
     * the application being replaced, and our read path is the Business API, so
     * a row there would never be seen on this side.
     *
     * One row per membership, replaced whole, because the modal always submits
     * all four fields pre-filled with the values on screen.
     */
    @Transactional
    public Map<String, Object> saveContactOverride(String membership, Map<String, Object> body) {
        String address  = trimmed(body.get("addressLine1"));
        String phone    = trimmed(body.get("cellPhone"));
        String email    = trimmed(body.get("email"));
        String business = trimmed(body.get("businessName"));

        // Legacy's guard (edit.php:3597): refuse a save where every field is
        // blank, which would only wipe the API data for no reason.
        // A ResponseStatusException rather than IllegalArgumentException so the
        // caller gets a 400: GlobalExceptionHandler maps anything implementing
        // ErrorResponse to its own status and everything else to a 500.
        if (isBlank(address) && isBlank(phone) && isBlank(email) && isBlank(business)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one contact field is required");
        }

        MemberContactOverride override = overrideRepository.findByMembershipNumber(membership)
                .orElseGet(() -> MemberContactOverride.builder().membershipNumber(membership).build());
        override.setAddressLine1(address);
        override.setPhone(phone);
        override.setEmail(email);
        override.setBusinessName(business);
        if (body.get("userId") != null) {
            override.setCreatedByUserId(Integer.valueOf(body.get("userId").toString()));
        }
        overrideRepository.save(override);
        log.info("[MemberService] contact override saved membership={}", membership);

        return getMember(membership);
    }

    /** Legacy-compatible name search used when the operator enters a member name. */
    public List<Map<String, Object>> searchMembers(String name) {
        String term = name == null ? "" : name.trim();
        if (term.length() < 2) return List.of();
        return nativeQueries.list(
            "SELECT tarjeta, nombre, correo, ps_tienda_id, ps_pais_iso2 " +
            "FROM ps_socios_dqs20 WHERE nombre LIKE ? ORDER BY nombre LIMIT 50",
            "%" + term.replace("%", "\\%") + "%");
    }

    private static String trimmed(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
