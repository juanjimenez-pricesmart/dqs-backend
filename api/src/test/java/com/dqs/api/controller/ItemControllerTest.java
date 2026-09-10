package com.dqs.api.controller;

import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.ItemService;
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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ItemController(itemService))
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
    @DisplayName("a search without a club or a term is refused")
    void searchRequiresBoth() throws Exception {
        mvc.perform(get("/api/v1/items/search").param("clubId", "6101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/items/search").param("q", "arroz"))
                .andExpect(status().isBadRequest());
    }
}
