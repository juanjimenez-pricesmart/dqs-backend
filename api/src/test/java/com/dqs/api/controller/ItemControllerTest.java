package com.dqs.api.controller;

import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.dto.PresetAmountResponse;
import com.dqs.api.service.ItemService;
import com.dqs.api.service.PresetAmountService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog endpoints. Two of them, and they are deliberately separate: a
 * code lookup and a description search. Sending a code to the description
 * endpoint answers 404, which is the bug that made picking a search result
 * fail, so the split has a test of its own here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ItemControllerTest {

    @Mock private ItemService itemService;
    @Mock private PresetAmountService presetAmountService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ItemController(itemService, presetAmountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a code is looked up for one club, since price and stock are per club")
    void codeLookupIsPerClub() throws Exception {
        when(itemService.getItemByCode("1001", 6101))
                .thenReturn(Map.of("item_code", "1001", "description", "ARROZ 5KG", "quantityOnHand", 40));

        mvc.perform(get("/api/v1/items/1001/club/6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_code").value("1001"))
                .andExpect(jsonPath("$.quantityOnHand").value(40));
    }

    @Test
    @DisplayName("a description search is the other endpoint, and takes its club as a parameter")
    void searchIsSeparate() throws Exception {
        when(itemService.searchItems(6101, "arroz"))
                .thenReturn(List.of(Map.of("item_code", "1001")));

        mvc.perform(get("/api/v1/items/search").param("clubId", "6101").param("q", "arroz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].item_code").value("1001"));
    }

    @Test
    @DisplayName("the preset amounts hang off the same code-and-club route")
    void presetAmountsAreScopedToAClub() throws Exception {
        when(presetAmountService.getForProductAndClub("999979", 6401)).thenReturn(List.of(
                PresetAmountResponse.builder()
                        .denominationUsd(new BigDecimal("20.00")).localAmount(new BigDecimal("10000.00"))
                        .displayOrder(1).currencyCode("CRC").currencySymbol("₡").build()));

        // The amount that goes on the line is the local one; the denomination
        // is what the customer recognises. Both have to survive the wire.
        mvc.perform(get("/api/v1/items/999979/club/6401/preset-amounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].denominationUsd").value(20.00))
                .andExpect(jsonPath("$[0].localAmount").value(10000.00))
                .andExpect(jsonPath("$[0].currencySymbol").value("₡"));
    }

    @Test
    @DisplayName("a product with no preset amounts answers an empty list, not 404")
    void noPresetAmountsIsAnEmptyList() throws Exception {
        when(presetAmountService.getForProductAndClub("1001", 6401)).thenReturn(List.of());

        // The screen asks for every item it opens and shows the dropdown only
        // when something comes back; a 404 would have to be caught instead.
        mvc.perform(get("/api/v1/items/1001/club/6401/preset-amounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a search without a club or a term is refused")
    void searchRequiresBoth() throws Exception {
        mvc.perform(get("/api/v1/items/search").param("clubId", "6101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/items/search").param("q", "arroz"))
                .andExpect(status().isBadRequest());
    }
}
