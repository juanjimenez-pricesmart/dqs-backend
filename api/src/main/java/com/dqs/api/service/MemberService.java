package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dqs.api.repository.support.NativeQueries;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;
    private final NativeQueries nativeQueries;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMember(String membership) {
        log.info("[MemberService] getMember membership={}", membership);
        String response = businessApiClient.get("/api/membership/validate/" + membership);
        try {
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[MemberService] Error parsing member response: {}", e.getMessage());
            throw new RuntimeException("Error consultando membresía: " + e.getMessage());
        }
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
}
