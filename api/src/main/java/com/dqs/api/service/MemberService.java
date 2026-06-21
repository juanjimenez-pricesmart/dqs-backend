package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;

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
}
