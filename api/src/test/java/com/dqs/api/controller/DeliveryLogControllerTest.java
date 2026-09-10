package com.dqs.api.controller;

import com.dqs.api.dto.DeliveryLogResponse;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.DeliveryLogPdfService;
import com.dqs.api.service.DeliveryLogService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery-log endpoints.
 *
 * This controller does its own coercion, which is where the interesting part
 * is: a browser sends quotation ids as JSON numbers and a hand-rolled request
 * or a form sends them as strings, and both have to load the same truck. So
 * both shapes are exercised, along with what happens when the field is missing
 * or is not a list at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryLogControllerTest {

    @Mock private DeliveryLogService deliveryLogService;
    @Mock private DeliveryLogPdfService pdfService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DeliveryLogController(deliveryLogService, pdfService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static DeliveryLogResponse log() {
        return DeliveryLogResponse.builder()
                .logId(9L).storeId(6101).statusId(2).createdBy(1)
                .fecha("2026-09-15").deliveryCount(2)
                .deliveries(List.of(Map.of("quotationId", 107L)))
                .build();
    }

    @Test
    @DisplayName("the deliveries available to load are asked for by club and route")
    void availableNeedsClubAndRoute() throws Exception {
        when(deliveryLogService.getAvailableDeliveries(6101, "6101 01"))
                .thenReturn(List.of(Map.of("quotationId", 107L)));

        mvc.perform(get("/api/v1/delivery-logs/available")
                        .param("storeId", "6101").param("routeId", "6101 01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quotationId").value(107));
    }

    @Test
    @DisplayName("asking without a route is refused — it would return the whole club")
    void availableRequiresRoute() throws Exception {
        // A log without a route would let an operator load a truck with another
        // route's stops.
        mvc.perform(get("/api/v1/delivery-logs/available").param("storeId", "6101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("creating a log takes ids as numbers")
    void createReadsNumericIds() throws Exception {
        when(deliveryLogService.createLog(anyInt(), any(), anyInt(), anyString())).thenReturn(log());

        mvc.perform(post("/api/v1/delivery-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":6101,\"routeId\":\"6101 01\",\"createdBy\":7,\"quotationIds\":[107,108]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logId").value(9))
                .andExpect(jsonPath("$.deliveryCount").value(2));

        verify(deliveryLogService).createLog(6101, List.of(107L, 108L), 7, "6101 01");
    }

    @Test
    @DisplayName("and takes the same ids as strings, which is how a form sends them")
    void createReadsTextualIds() throws Exception {
        when(deliveryLogService.createLog(anyInt(), any(), anyInt(), anyString())).thenReturn(log());

        mvc.perform(post("/api/v1/delivery-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"6101\",\"routeId\":\"6101 01\",\"createdBy\":\"7\",\"quotationIds\":[\"107\",\"108\"]}"))
                .andExpect(status().isOk());

        verify(deliveryLogService).createLog(6101, List.of(107L, 108L), 7, "6101 01");
    }

    @Test
    @DisplayName("a create with nothing in it becomes club zero, user one and no deliveries")
    void createDefaultsEverything() throws Exception {
        when(deliveryLogService.createLog(anyInt(), any(), anyInt(), anyString())).thenReturn(log());

        mvc.perform(post("/api/v1/delivery-logs")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // createdBy falls back to 1 — the placeholder user, until auth exists.
        verify(deliveryLogService).createLog(0, List.of(), 1, "");
    }

    @Test
    @DisplayName("ids sent as something other than a list are read as none")
    void createIgnoresNonListIds() throws Exception {
        when(deliveryLogService.createLog(anyInt(), any(), anyInt(), anyString())).thenReturn(log());

        mvc.perform(post("/api/v1/delivery-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":6101,\"quotationIds\":\"107\"}"))
                .andExpect(status().isOk());

        verify(deliveryLogService).createLog(eq(6101), eq(List.of()), anyInt(), anyString());
    }

    @Test
    @DisplayName("adding to an open log carries the log id from the path")
    void addToLogUsesPathId() throws Exception {
        when(deliveryLogService.addToLog(anyLong(), any())).thenReturn(log());

        mvc.perform(post("/api/v1/delivery-logs/9/add")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quotationIds\":[108]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logId").value(9));

        verify(deliveryLogService).addToLog(9L, List.of(108L));
    }

    @Test
    @DisplayName("the active and historical lists are separate endpoints on the same pair of filters")
    void activeAndHistoricalAreSeparate() throws Exception {
        when(deliveryLogService.getActiveLogs(6101, "6101 01")).thenReturn(List.of(log()));
        when(deliveryLogService.getHistoricalLogs(6101, "6101 01")).thenReturn(List.of());

        mvc.perform(get("/api/v1/delivery-logs/active")
                        .param("storeId", "6101").param("routeId", "6101 01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].statusId").value(2));

        mvc.perform(get("/api/v1/delivery-logs/historical")
                        .param("storeId", "6101").param("routeId", "6101 01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("closing a log reports whether a row actually changed")
    void closeReportsWhetherItChanged() throws Exception {
        when(deliveryLogService.closeLog(9L)).thenReturn(true);
        mvc.perform(patch("/api/v1/delivery-logs/9/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // A log that was already closed changed nothing, and says so.
        when(deliveryLogService.closeLog(9L)).thenReturn(false);
        mvc.perform(patch("/api/v1/delivery-logs/9/close"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("the log PDF comes back as an attachment named after the log")
    void pdfIsAnAttachment() throws Exception {
        when(pdfService.generatePdf(9L)).thenReturn(new byte[] { 1, 2, 3 });

        mvc.perform(get("/api/v1/delivery-logs/9/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"delivery-log-9.pdf\""));
    }

    @Test
    @DisplayName("a PDF that cannot be built is a server error, not an empty file")
    void pdfFailureIsAServerError() throws Exception {
        when(pdfService.generatePdf(anyLong())).thenThrow(new RuntimeException("no such log"));

        // An empty 200 would download as a corrupt PDF and look like our bug.
        mvc.perform(get("/api/v1/delivery-logs/9/pdf"))
                .andExpect(status().isInternalServerError());
    }
}
