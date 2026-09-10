package com.dqs.api.controller;

import com.dqs.api.exception.BusinessApiException;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The membership endpoints, both of them proxies onto the Business API. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberControllerTest {

    @Mock private MemberService memberService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(memberService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a membership is fetched by its number")
    void memberByMembership() throws Exception {
        when(memberService.getMember("70012345678901"))
                .thenReturn(Map.of("name", "JUAN PEREZ", "cardStatusCode", "1"));

        mvc.perform(get("/api/v1/members/70012345678901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("JUAN PEREZ"));
    }

    @Test
    @DisplayName("a membership the Business API does not know is a 404, not a 500")
    void unknownMembershipIsNotFound() throws Exception {
        when(memberService.getMember(anyString()))
                .thenThrow(new BusinessApiException(404, "/members/70012345678901", "not found"));

        // Flattening this into a 500 is what made a missing membership read as
        // "Internal server error".
        mvc.perform(get("/api/v1/members/70012345678901"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No encontrado en el Business API"));
    }

    @Test
    @DisplayName("an upstream failure is a bad gateway, because it is not our fault")
    void upstreamFailureIsBadGateway() throws Exception {
        when(memberService.getMember(anyString()))
                .thenThrow(new BusinessApiException(503, "/members/70012345678901", "unavailable"));

        mvc.perform(get("/api/v1/members/70012345678901"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Business API respondió 503"));
    }

    @Test
    @DisplayName("members are searched by name")
    void searchByName() throws Exception {
        when(memberService.searchMembers("perez"))
                .thenReturn(List.of(Map.of("name", "JUAN PEREZ")));

        mvc.perform(get("/api/v1/members/search").param("name", "perez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("JUAN PEREZ"));
    }

    @Test
    @DisplayName("a search with no name is refused rather than returning everyone")
    void searchRequiresName() throws Exception {
        mvc.perform(get("/api/v1/members/search")).andExpect(status().isBadRequest());
    }
}
