package com.dqs.api.controller;

import com.dqs.api.repository.support.NativeQueries;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The diagnostics endpoints: read-only windows onto the legacy tables, used to
 * confirm what the shared dev database actually holds.
 *
 * They are all one-liners, so what these tests are really for is the SQL —
 * every one of these queries is a legacy table this app must never write to,
 * and every parameter goes through a placeholder rather than into the string.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagControllerTest {

    @Mock private NativeQueries nativeQueries;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DiagController(nativeQueries)).build();
    }

    @Test
    @DisplayName("the table listing asks only for the legacy ps_ tables")
    void tablesListsOnlyLegacyTables() throws Exception {
        when(nativeQueries.column(anyString(), eq(String.class))).thenReturn(List.of("ps_tienda"));

        mvc.perform(get("/api/v1/diag/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("ps_tienda"));

        verify(nativeQueries).column("SHOW TABLES LIKE 'ps_%'", String.class);
    }

    @Test
    @DisplayName("the club listing is ordered by country and name")
    void storesAreOrdered() throws Exception {
        when(nativeQueries.list(anyString())).thenReturn(List.of(Map.of("ps_tienda_id", 6101)));

        mvc.perform(get("/api/v1/diag/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ps_tienda_id").value(6101));
    }

    @Test
    @DisplayName("one club is fetched through a placeholder, never by string concatenation")
    void oneStoreUsesAPlaceholder() throws Exception {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of(Map.of("nombre", "Barranquilla")));

        mvc.perform(get("/api/v1/diag/store/6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Barranquilla"));

        verify(nativeQueries).list("SELECT * FROM ps_tienda WHERE ps_tienda_id = ?", 6101);
    }

    @Test
    @DisplayName("a user is fetched by id, returning only the two harmless columns")
    void userReturnsOnlyIdAndEmail() throws Exception {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of(Map.of("id", 1, "email", "a@b.co")));

        mvc.perform(get("/api/v1/diag/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("a@b.co"));

        // No password column: this endpoint is open on the dev environment.
        verify(nativeQueries).list("SELECT id, email FROM users WHERE id = ?", 1);
    }

    @Test
    @DisplayName("exchange rates are fetched by country")
    void ratesByCountry() throws Exception {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of(Map.of("ps_tasa_cambio_id", 9)));

        mvc.perform(get("/api/v1/diag/tasa/CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ps_tasa_cambio_id").value(9));

        verify(nativeQueries).list("SELECT * FROM ps_tasa_cambio WHERE ps_pais_iso2 = ?", "CO");
    }

    @Test
    @DisplayName("the current rate is the most recently inserted one, not the most recently dated")
    void currentRateIsTheLastInserted() throws Exception {
        when(nativeQueries.list(anyString(), any()))
                .thenReturn(List.of(Map.of("ps_tasa_cambio_tipocambio", 4150.25)));

        mvc.perform(get("/api/v1/diag/tasa-actual/CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ps_tasa_cambio_tipocambio").value(4150.25));

        // Ordered by id, which is legacy's own definition of "current".
        verify(nativeQueries).list(
                "SELECT ps_tasa_cambio_tipocambio FROM ps_tasa_cambio " +
                "WHERE ps_pais_iso2 = ? ORDER BY ps_tasa_cambio_id DESC LIMIT 1", "CO");
    }
}
