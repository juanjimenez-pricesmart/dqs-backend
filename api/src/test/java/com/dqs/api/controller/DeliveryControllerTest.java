package com.dqs.api.controller;

import com.dqs.api.catalog.source.DeliveryCityInfo;
import com.dqs.api.catalog.source.RouteInfo;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.DeliveryService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery endpoints.
 *
 * Two of them catch their own exceptions and answer a body rather than letting
 * the advice map them, and they do it differently from each other — a failed
 * save is a 500 and a failed delete is a 200 with success:false. That asymmetry
 * is what the frontend was written against, so it is pinned here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryControllerTest {

    @Mock private DeliveryService deliveryService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DeliveryController(deliveryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("windows default to pick-up in club when no type is named")
    void windowsDefaultToPickUp() throws Exception {
        when(deliveryService.getDeliveryWindows(any(), any(), any())).thenReturn(Map.of("windows", List.of()));

        mvc.perform(get("/api/v1/deliveries/windows")
                        .param("clubId", "6101").param("dateTime", "2026-09-15T08:00:00"))
                .andExpect(status().isOk());

        verify(deliveryService).getDeliveryWindows(6101, "2026-09-15T08:00:00", "PICK_UP_IN_CLUB");
    }

    @Test
    @DisplayName("a delivery route asks for that route's windows")
    void windowsHonourTheType() throws Exception {
        mvc.perform(get("/api/v1/deliveries/windows")
                        .param("clubId", "6101").param("dateTime", "2026-09-15T08:00:00")
                        .param("type", "INTERCITY_DELIVERY"))
                .andExpect(status().isOk());

        verify(deliveryService).getDeliveryWindows(6101, "2026-09-15T08:00:00", "INTERCITY_DELIVERY");
    }

    @Test
    @DisplayName("windows cannot be asked for without a club and a moment")
    void windowsRequireClubAndDateTime() throws Exception {
        mvc.perform(get("/api/v1/deliveries/windows").param("clubId", "6101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the routes of a club come back with their tariffs")
    void routesReturnTariffs() throws Exception {
        when(deliveryService.getRoutes(6101)).thenReturn(List.of(new RouteInfo(
                "6101 01", "Barranquilla Dry", 12.0, 256.0, 11, 43.0, 6, 21.0, 5.0,
                "Intercity Delivery", "INTERCITY_DELIVERY")));

        mvc.perform(get("/api/v1/deliveries/routes").param("storeId", "6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("6101 01"))
                .andExpect(jsonPath("$[0].palletRate").value(256.0))
                .andExpect(jsonPath("$[0].palletMinimum").value(11));
    }

    @Test
    @DisplayName("the delivery cities of a country come back by ISO2")
    void citiesReturnByCountry() throws Exception {
        when(deliveryService.getCities("CO")).thenReturn(List.of(new DeliveryCityInfo("1", "BARRANQUILLA")));

        mvc.perform(get("/api/v1/deliveries/cities").param("countryIso2", "CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("BARRANQUILLA"));
    }

    @Test
    @DisplayName("the last address a member had delivered to is wrapped in a field")
    void lastAddressIsWrapped() throws Exception {
        when(deliveryService.getLastDeliveryAddress("70012345678901")).thenReturn("CRA 43 N 82 66");

        mvc.perform(get("/api/v1/deliveries/last-address").param("membership", "70012345678901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("CRA 43 N 82 66"));
    }

    @Test
    @DisplayName("saving a delivery answers success")
    void saveAnswersSuccess() throws Exception {
        mvc.perform(post("/api/v1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotation_id\":107,\"address\":\"CRA 43\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(deliveryService).saveDelivery(Map.of("quotation_id", 107, "address", "CRA 43"));
    }

    @Test
    @DisplayName("a save that fails answers 500, so the frontend does not report a saved delivery")
    void saveAnswers500OnFailure() throws Exception {
        doThrow(new IllegalArgumentException("quotation_id is required to save a delivery"))
                .when(deliveryService).saveDelivery(any());

        mvc.perform(post("/api/v1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Error saving delivery"));
    }

    @Test
    @DisplayName("a quotation with a delivery returns it")
    void getReturnsDelivery() throws Exception {
        when(deliveryService.getDelivery(107L)).thenReturn(Map.of("address", "CRA 43", "amount", 500));

        mvc.perform(get("/api/v1/deliveries/107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("CRA 43"));
    }

    @Test
    @DisplayName("a quotation with no delivery answers 204, not an empty object")
    void getAnswers204WhenAbsent() throws Exception {
        when(deliveryService.getDelivery(107L)).thenReturn(null);

        // The frontend distinguishes "no delivery" from "a delivery with no
        // fields", and this status is how.
        mvc.perform(get("/api/v1/deliveries/107"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("deleting a delivery answers success")
    void deleteAnswersSuccess() throws Exception {
        when(deliveryService.deleteDelivery(107L)).thenReturn(true);

        mvc.perform(delete("/api/v1/deliveries/107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("a delete that fails answers 200 with success false — unlike the save")
    void deleteAnswers200OnFailure() throws Exception {
        when(deliveryService.deleteDelivery(anyLong())).thenThrow(new RuntimeException("row locked"));

        mvc.perform(delete("/api/v1/deliveries/107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("a delete the service declines still answers success true — it reports no failure")
    void deleteReportsServiceResultAsSuccess() throws Exception {
        // The controller does not read the boolean the service returns; it only
        // reports whether the call threw. Pinned so the asymmetry is visible.
        when(deliveryService.deleteDelivery(107L)).thenReturn(false);

        mvc.perform(delete("/api/v1/deliveries/107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(deliveryService).deleteDelivery(eq(107L));
    }
}
