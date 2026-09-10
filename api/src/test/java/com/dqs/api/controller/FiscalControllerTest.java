package com.dqs.api.controller;

import com.dqs.api.catalog.source.DocTypeInfo;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.FiscalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fiscal endpoints: the GoSocket validation and the four catalogs behind
 * the invoice form.
 *
 * The catalogs are a chain — a country picks its cities, a city its zones, a
 * zone its neighbourhoods — and the neighbourhood endpoint needs both levels,
 * because a zone code repeats across cities. That requirement is asserted here
 * as a bad request rather than left to fail as a wrong list.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiscalControllerTest {

    @Mock private FiscalService fiscalService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FiscalController(fiscalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a document code is validated with its type when one is given")
    void validateWithType() throws Exception {
        when(fiscalService.validateIdentification("900123456", "NIT"))
                .thenReturn(Map.of("valid", true, "name", "ACME SA"));

        mvc.perform(get("/api/v1/fiscal/validate").param("code", "900123456").param("type", "NIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.name").value("ACME SA"));
    }

    @Test
    @DisplayName("the type is optional — the service decides what to do without one")
    void validateWithoutType() throws Exception {
        mvc.perform(get("/api/v1/fiscal/validate").param("code", "900123456"))
                .andExpect(status().isOk());

        verify(fiscalService).validateIdentification(eq("900123456"), isNull());
    }

    @Test
    @DisplayName("validation cannot be asked for without a code")
    void validateRequiresCode() throws Exception {
        mvc.perform(get("/api/v1/fiscal/validate")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saving the invoice data answers success")
    void saveAnswersSuccess() throws Exception {
        when(fiscalService.saveFiscalData(any())).thenReturn(true);

        mvc.perform(post("/api/v1/fiscal/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotation_id\":107,\"membership\":\"70012345678901\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("a save the service declines is a 500, so nothing reports an invoice that was not stored")
    void saveAnswers500WhenDeclined() throws Exception {
        when(fiscalService.saveFiscalData(any())).thenReturn(false);

        mvc.perform(post("/api/v1/fiscal/save")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Error saving fiscal data"));
    }

    @Test
    @DisplayName("a quotation with invoice data returns it")
    void dataReturnsRow() throws Exception {
        when(fiscalService.getFiscalDataByQuotation(107L))
                .thenReturn(Map.of("businessName", "ACME SA", "docNumber", "900123456"));

        mvc.perform(get("/api/v1/fiscal/data").param("quotationId", "107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("ACME SA"));
    }

    @Test
    @DisplayName("a quotation with no invoice data answers 204, not an empty object")
    void dataAnswers204WhenAbsent() throws Exception {
        when(fiscalService.getFiscalDataByQuotation(107L)).thenReturn(null);

        mvc.perform(get("/api/v1/fiscal/data").param("quotationId", "107"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("the economic activities of a country come back as a list")
    void economicActivities() throws Exception {
        when(fiscalService.getEconomicActivities("CO"))
                .thenReturn(List.of(Map.of("code", "4711", "name", "Comercio al por menor")));

        mvc.perform(get("/api/v1/fiscal/catalog/economic-activities").param("country", "CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("4711"));
    }

    @Test
    @DisplayName("the cities of a country come back as a list")
    void cities() throws Exception {
        when(fiscalService.getCities("CO")).thenReturn(List.of(Map.of("code", "08001")));

        mvc.perform(get("/api/v1/fiscal/catalog/cities").param("country", "CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("08001"));
    }

    @Test
    @DisplayName("zones are asked for by city")
    void zones() throws Exception {
        when(fiscalService.getZones("08001")).thenReturn(List.of(Map.of("code", "01")));

        mvc.perform(get("/api/v1/fiscal/catalog/zones").param("cityCode", "08001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("01"));
    }

    @Test
    @DisplayName("neighbourhoods need the zone AND the city, since a zone code repeats between cities")
    void neighborhoodsNeedBothLevels() throws Exception {
        when(fiscalService.getNeighborhoods("01", "08001")).thenReturn(List.of(Map.of("name", "EL PRADO")));

        mvc.perform(get("/api/v1/fiscal/catalog/neighborhoods")
                        .param("zoneCode", "01").param("cityCode", "08001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("EL PRADO"));

        // Keyed on the zone alone, one city's neighbourhoods would be served
        // for another's.
        mvc.perform(get("/api/v1/fiscal/catalog/neighborhoods").param("zoneCode", "01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the document types of a country come back with their legacy field names")
    void docTypes() throws Exception {
        when(fiscalService.getDocTypes("CO"))
                .thenReturn(List.of(new DocTypeInfo(1, "Factura electrónica", "FE", "01")));

        mvc.perform(get("/api/v1/fiscal/catalog/doc-types").param("country", "CO"))
                .andExpect(status().isOk())
                // The JSON keys are legacy's, so the frontend reads one shape
                // whichever catalog source is switched on.
                .andExpect(jsonPath("$[0].felid").value(1))
                .andExpect(jsonPath("$[0].descripcion").value("Factura electrónica"));
    }
}
