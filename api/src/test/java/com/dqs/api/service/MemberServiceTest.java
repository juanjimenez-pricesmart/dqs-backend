package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;
import com.dqs.api.repository.MemberContactOverrideRepository;
import com.dqs.api.repository.support.NativeQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock private BusinessApiClient businessApiClient;
    @Mock private NativeQueries nativeQueries;
    @Mock private MemberContactOverrideRepository overrideRepository;

    /** The real mapper: parsing is what this service does, so mocking it would test nothing. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MemberService service() {
        return new MemberService(businessApiClient, objectMapper, nativeQueries, overrideRepository);
    }

    @Test
    @DisplayName("returns the membership payload as a map")
    void parsesTheMembership() {
        when(businessApiClient.get("/api/membership/validate/61010091080001"))
            .thenReturn("{\"firstName\":\"WILLIAM\",\"cardStatusCode\":\"59\"}");
        when(overrideRepository.findByMembershipNumber("61010091080001")).thenReturn(Optional.empty());

        Map<String, Object> member = service().getMember("61010091080001");

        assertThat(member).containsEntry("firstName", "WILLIAM")
                          .containsEntry("cardStatusCode", "59");
    }

    @Test
    @DisplayName("a payload that is not JSON fails loudly rather than returning nothing")
    void unparseableResponseThrows() {
        when(businessApiClient.get(anyString())).thenReturn("<html>gateway error</html>");

        assertThatThrownBy(() -> service().getMember("1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Error consultando membresía");
    }

    // ── name search ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a term under two characters never reaches the database")
    void shortTermIsNotSearched() {
        assertThat(service().searchMembers("a")).isEmpty();
        assertThat(service().searchMembers("")).isEmpty();
        verifyNoInteractions(nativeQueries);
    }

    @Test
    @DisplayName("a null term is treated as empty rather than throwing")
    void nullTermIsEmpty() {
        assertThat(service().searchMembers(null)).isEmpty();
        verify(nativeQueries, never()).list(anyString(), anyString());
    }

    @Test
    @DisplayName("the term is trimmed before being measured")
    void surroundingSpaceDoesNotCountTowardTheMinimum() {
        assertThat(service().searchMembers("  a  ")).isEmpty();
        verifyNoInteractions(nativeQueries);
    }

    @Test
    @DisplayName("a usable term is wrapped in wildcards")
    void searchesWithWildcards() {
        when(nativeQueries.list(anyString(), anyString()))
            .thenReturn(List.of(Map.of("nombre", "WILLIAM EL KARAAN")));

        List<Map<String, Object>> found = service().searchMembers(" will ");

        assertThat(found).hasSize(1);
        ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).list(anyString(), arg.capture());
        assertThat(arg.getValue()).isEqualTo("%will%");
    }

    @Test
    @DisplayName("a percent sign in the term is escaped, not treated as a wildcard")
    void percentInTermIsEscaped() {
        when(nativeQueries.list(anyString(), anyString())).thenReturn(List.of());

        service().searchMembers("50%off");

        ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).list(anyString(), arg.capture());
        // Left unescaped it would match every member whose name starts with "50"
        assertThat(arg.getValue()).isEqualTo("%50\\%off%");
    }
}
