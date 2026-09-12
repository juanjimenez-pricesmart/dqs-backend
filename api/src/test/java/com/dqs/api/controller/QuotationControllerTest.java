package com.dqs.api.controller;

import com.dqs.api.dto.BankTransferNoticeResponse;
import com.dqs.api.dto.CloseQuotationRequest;
import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.QuotationItemRequest;
import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SendToOmsRequest;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.repository.QuotationListRepository;
import com.dqs.api.service.BankTransferNoticeService;
import com.dqs.api.service.QuotationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The quotation endpoints, through the real routing.
 *
 * These go through MockMvc rather than calling the methods directly, because
 * half of what a controller contributes lives in the annotations: the paths,
 * the parameter defaults, the status codes, and which of two overlapping routes
 * wins. A direct call exercises none of that.
 *
 * The advice is registered so the statuses a client actually sees are asserted
 * here — a missing quotation as 404 rather than as an escaped exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationControllerTest {

    @Mock private QuotationService quotationService;
    @Mock private com.dqs.api.service.SeasonService seasonService;
    @Mock private com.dqs.api.service.QuoteEmailService quoteEmailService;
    @Mock private QuotationListRepository quotationListRepository;
    @Mock private BankTransferNoticeService bankTransferNoticeService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new QuotationController(
                        quotationService, seasonService, quoteEmailService,
                        quotationListRepository, bankTransferNoticeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static QuotationResponse response() {
        return QuotationResponse.builder()
                .id(107L).storeId(6101).userId(1).statusId(1)
                .customerName("ACME").customerMembership("70012345678901")
                .netAmount(new BigDecimal("1190.00"))
                .build();
    }

    private static QuotationItemResponse itemResponse() {
        return QuotationItemResponse.builder()
                .id(500L).quotationId(107L).productId("1001").qty(new BigDecimal("3"))
                .amount(new BigDecimal("357")).description("ARROZ 5KG")
                .build();
    }

    private String body(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    // ── The list ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the list defaults to pending quotations of the whole club")
    void listDefaultsToPendingAndEveryone() throws Exception {
        when(quotationListRepository.findByStoreFiltered(anyInt(), anyInt(), any(Boolean.class), any(), any()))
                .thenReturn(List.of(Map.of("id", 107)));

        mvc.perform(get("/api/v1/quotations").param("storeId", "6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(107));

        // statusId defaults to 1 and scope to "all", so mineOnly is false and
        // the userId is not needed.
        verify(quotationListRepository).findByStoreFiltered(6101, 1, false, null, null);
    }

    @Test
    @DisplayName("scope=mine narrows the list to one user, whatever the casing")
    void listNarrowsToMine() throws Exception {
        mvc.perform(get("/api/v1/quotations")
                        .param("storeId", "6101").param("scope", "MINE").param("userId", "1"))
                .andExpect(status().isOk());

        verify(quotationListRepository).findByStoreFiltered(6101, 1, true, 1, null);
    }

    @Test
    @DisplayName("the status and period filters are passed through as given")
    void listPassesFiltersThrough() throws Exception {
        mvc.perform(get("/api/v1/quotations")
                        .param("storeId", "6101").param("statusId", "3").param("periodId", "42"))
                .andExpect(status().isOk());

        verify(quotationListRepository).findByStoreFiltered(6101, 3, false, null, 42);
    }

    @Test
    @DisplayName("a list request with no club is a bad request, not an unfiltered dump")
    void listRequiresAStore() throws Exception {
        mvc.perform(get("/api/v1/quotations")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the pending deliveries of a club are their own endpoint")
    void deliveriesEndpoint() throws Exception {
        when(quotationListRepository.findDeliveriesByStore(6101))
                .thenReturn(List.of(Map.of("quotationId", 107)));

        mvc.perform(get("/api/v1/quotations/deliveries").param("storeId", "6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quotationId").value(107));
    }

    @Test
    @DisplayName("the summary takes an optional period")
    void summaryEndpoint() throws Exception {
        when(quotationListRepository.findSummaryByStatus(eq(6101), any()))
                .thenReturn(List.of(Map.of("statusId", 1, "count", 4)));

        mvc.perform(get("/api/v1/quotations/summary").param("storeId", "6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(4));
        verify(quotationListRepository).findSummaryByStatus(6101, null);

        mvc.perform(get("/api/v1/quotations/summary").param("storeId", "6101").param("periodId", "42"))
                .andExpect(status().isOk());
        verify(quotationListRepository).findSummaryByStatus(6101, 42);
    }

    @Test
    @DisplayName("the closing periods need no parameters at all")
    void periodsEndpoint() throws Exception {
        when(quotationListRepository.findPeriods()).thenReturn(List.of(Map.of("id", 42)));

        mvc.perform(get("/api/v1/quotations/periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42));
    }

    // ── Create, read ──────────────────────────────────────────────────────

    @Test
    @DisplayName("creating a quotation answers 201 with the new row")
    void createAnswers201() throws Exception {
        when(quotationService.createQuotation(any())).thenReturn(response());
        CreateQuotationRequest req = new CreateQuotationRequest();
        req.setCustomerName("ACME");
        req.setStoreId(6101);
        req.setUserId(1);

        mvc.perform(post("/api/v1/quotations").contentType(MediaType.APPLICATION_JSON).content(body(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(107));
    }

    @Test
    @DisplayName("a create missing the required fields is refused with the field names")
    void createRejectsIncompleteBody() throws Exception {
        mvc.perform(post("/api/v1/quotations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.customerName").exists())
                .andExpect(jsonPath("$.storeId").exists())
                .andExpect(jsonPath("$.userId").exists());
    }

    @Test
    @DisplayName("reading one quotation returns it")
    void getByIdReturnsQuotation() throws Exception {
        when(quotationService.getById(107L)).thenReturn(response());

        mvc.perform(get("/api/v1/quotations/107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerMembership").value("70012345678901"));
    }

    @Test
    @DisplayName("an unknown quotation is a 404, not an escaped exception")
    void getByIdAnswers404() throws Exception {
        when(quotationService.getById(404L)).thenThrow(new QuotationNotFoundException(404L));

        mvc.perform(get("/api/v1/quotations/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Quotation not found: 404"));
    }

    @Test
    @DisplayName("/cancel-reasons is the reasons list, not a quotation with that id")
    void cancelReasonsWinsOverTheIdRoute() throws Exception {
        when(quotationService.getCancelReasons())
                .thenReturn(List.of(Map.of("id", 1, "description", "Existencias insuficientes")));

        // Two routes could match this path — /{id} and the literal one. A literal
        // segment wins, and this is the test that says so.
        mvc.perform(get("/api/v1/quotations/cancel-reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // ── Cancel, submit, close ─────────────────────────────────────────────

    @Test
    @DisplayName("cancelling answers 204 and passes the reason through")
    void cancelAnswers204() throws Exception {
        mvc.perform(patch("/api/v1/quotations/107/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":4}"))
                .andExpect(status().isNoContent());

        verify(quotationService).cancelQuotation(107L, 4);
    }

    @Test
    @DisplayName("a reason sent as text is still read as a number")
    void cancelParsesTextualReason() throws Exception {
        mvc.perform(patch("/api/v1/quotations/107/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":\"4\"}"))
                .andExpect(status().isNoContent());

        verify(quotationService).cancelQuotation(107L, 4);
    }

    @Test
    @DisplayName("a cancel with no reason reaches the service as none, for it to refuse")
    void cancelForwardsMissingReason() throws Exception {
        mvc.perform(patch("/api/v1/quotations/107/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        // The controller does not invent a default: the service owns that rule.
        verify(quotationService).cancelQuotation(eq(107L), isNull());
    }

    @Test
    @DisplayName("submitting returns the updated quotation")
    void submitReturnsQuotation() throws Exception {
        when(quotationService.submitQuotation(eq(107L), any())).thenReturn(response());
        SubmitQuotationRequest req = new SubmitQuotationRequest();
        req.setSubmittedBy(1);

        mvc.perform(post("/api/v1/quotations/107/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(body(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(107));
    }

    @Test
    @DisplayName("submitting a quote that already moved on is a conflict, not a 500")
    void submitAnswers409() throws Exception {
        when(quotationService.submitQuotation(anyLong(), any()))
                .thenThrow(new QuotationAlreadySubmittedException(107L, 3));

        mvc.perform(post("/api/v1/quotations/107/submit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"submittedBy\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Quotation 107 cannot be submitted — current status: 3"));
    }

    @Test
    @DisplayName("closing returns the quotation with its payment recorded")
    void closeReturnsQuotation() throws Exception {
        when(quotationService.closeQuotation(eq(107L), any())).thenReturn(response());
        CloseQuotationRequest req = new CloseQuotationRequest();
        req.setSubmittedBy(1);
        req.setQuoteTypeId(2);
        req.setPaymentNumber("201888600");
        req.setPaymentMethodId("110");

        mvc.perform(patch("/api/v1/quotations/107/close")
                        .contentType(MediaType.APPLICATION_JSON).content(body(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(107));
    }

    @Test
    @DisplayName("an amount the product is not sold at answers 400, not 500")
    void anInvalidPresetAmountIsABadRequest() throws Exception {
        when(quotationService.updateItem(eq(107L), eq(700L), any()))
                .thenThrow(new com.dqs.api.exception.InvalidPresetAmountException(
                        "Amount 1 is not offered for product 999979 at club 6401"));

        // The figure the caller sent is wrong, not our state. A 500 would read
        // to the screen as an outage and it would retry.
        mvc.perform(patch("/api/v1/quotations/107/items/700")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"presetAmount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("a sent quotation answers who received it")
    void emailAnswersTheRecipient() throws Exception {
        when(quoteEmailService.send(eq(107L), any())).thenReturn("socio@example.com");

        mvc.perform(post("/api/v1/quotations/107/email")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":5836}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value("socio@example.com"));

        verify(quoteEmailService).send(107L, 5836);
    }

    @Test
    @DisplayName("a quotation that cannot be emailed answers 400 with the reason")
    void aFailedEmailIsABadRequest() throws Exception {
        when(quoteEmailService.send(eq(107L), any()))
                .thenThrow(new com.dqs.api.exception.EmailNotSentException(
                        "The member has no valid email address on file"));

        // Legacy opens a popup and closes it after three seconds unless the
        // page happens to contain the word "error", so the advisor is never
        // told. The screen has to be able to say what went wrong.
        mvc.perform(post("/api/v1/quotations/107/email")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("the seasons list is scoped to the club that asks")
    void seasonsAreScopedToTheClub() throws Exception {
        when(seasonService.getActiveForClub(6101)).thenReturn(List.of(
                java.util.Map.of("tid", 12, "titulo", "Gift Program FY25")));

        mvc.perform(get("/api/v1/quotations/seasons").param("clubId", "6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].titulo").value("Gift Program FY25"));
    }

    @Test
    @DisplayName("a club with no seasons gets an empty list, not a 404")
    void aClubWithNoSeasonsGetsAnEmptyList() throws Exception {
        when(seasonService.getActiveForClub(6301)).thenReturn(List.of());

        // The select renders with only its placeholder; an error would make the
        // screen think something broke.
        mvc.perform(get("/api/v1/quotations/seasons").param("clubId", "6301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("clearing the season sends null rather than a magic zero")
    void clearingTheSeasonSendsNull() throws Exception {
        when(quotationService.updateSeason(eq(107L), any()))
                .thenReturn(QuotationResponse.builder().id(107L).build());

        mvc.perform(patch("/api/v1/quotations/107/season")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seasonId\":null}"))
                .andExpect(status().isOk());

        verify(quotationService).updateSeason(107L, null);
    }

    @Test
    @DisplayName("a season the club may not use answers 400")
    void anUnavailableSeasonIsABadRequest() throws Exception {
        when(quotationService.updateSeason(eq(107L), any()))
                .thenThrow(new com.dqs.api.exception.InvalidSeasonException(
                        "Season 12 is not available for club 6301"));

        mvc.perform(patch("/api/v1/quotations/107/season")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seasonId\":12}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("extending the expiry answers the quotation carrying its new date")
    void extendExpiryReturnsTheNewDate() throws Exception {
        when(quotationService.extendExpiry(107L)).thenReturn(
                QuotationResponse.builder().id(107L).expiryDate(java.time.LocalDate.of(2026, 10, 22)).build());

        // The screen replaces the expiry badge with whatever comes back, so the
        // date has to be in the body — a 204 would leave it showing the old one.
        mvc.perform(patch("/api/v1/quotations/107/extend-expiry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiryDate").value("2026-10-22"));
    }

    // ── Items ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adding a line answers 201 with the saved line")
    void saveItemAnswers201() throws Exception {
        when(quotationService.saveItem(eq(107L), any())).thenReturn(itemResponse());
        QuotationItemRequest req = new QuotationItemRequest();
        req.setProductId("1001");
        req.setQty(new BigDecimal("3"));

        mvc.perform(post("/api/v1/quotations/107/items")
                        .contentType(MediaType.APPLICATION_JSON).content(body(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value("1001"));
    }

    @Test
    @DisplayName("the lines of a quotation come back as a list")
    void getItemsReturnsList() throws Exception {
        when(quotationService.getItems(107L)).thenReturn(List.of(itemResponse()));

        mvc.perform(get("/api/v1/quotations/107/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("ARROZ 5KG"));
    }

    @Test
    @DisplayName("a pasted batch carries its club and its rows")
    void bulkPassesClubAndLines() throws Exception {
        when(quotationService.addItemsBulk(anyLong(), anyInt(), any()))
                .thenReturn(Map.of("added", List.of("1001"), "notFound", List.of(), "skipped", List.of()));

        mvc.perform(post("/api/v1/quotations/107/items/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clubId\":6101,\"lines\":[{\"productId\":\"1001\",\"qty\":\"2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[0]").value("1001"));

        verify(quotationService).addItemsBulk(107L, 6101,
                List.of(Map.of("productId", "1001", "qty", "2")));
    }

    @Test
    @DisplayName("a club sent as text is still read as a number")
    void bulkParsesTextualClub() throws Exception {
        mvc.perform(post("/api/v1/quotations/107/items/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clubId\":\"6101\",\"lines\":[]}"))
                .andExpect(status().isOk());

        verify(quotationService).addItemsBulk(107L, 6101, List.of());
    }

    @Test
    @DisplayName("a batch with no rows at all is an empty batch, not a null one")
    void bulkDefaultsToNoLines() throws Exception {
        mvc.perform(post("/api/v1/quotations/107/items/bulk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clubId\":6101}"))
                .andExpect(status().isOk());

        verify(quotationService).addItemsBulk(107L, 6101, List.of());
    }

    @Test
    @DisplayName("editing a line passes the whole patch body through")
    void updateItemPassesBody() throws Exception {
        when(quotationService.updateItem(anyLong(), anyLong(), any())).thenReturn(itemResponse());

        mvc.perform(patch("/api/v1/quotations/107/items/500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":5,\"exemp\":9.5,\"icomments\":\"bodega 3\"}"))
                .andExpect(status().isOk());

        verify(quotationService).updateItem(107L, 500L,
                Map.of("qty", 5, "exemp", 9.5, "icomments", "bodega 3"));
    }

    @Test
    @DisplayName("the narrow qty route reaches the narrow method")
    void updateItemQtyRoute() throws Exception {
        when(quotationService.updateItemQty(anyLong(), anyLong(), any())).thenReturn(itemResponse());

        mvc.perform(patch("/api/v1/quotations/107/items/500/qty")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":5}"))
                .andExpect(status().isOk());

        verify(quotationService).updateItemQty(107L, 500L, Map.of("qty", 5));
    }

    @Test
    @DisplayName("deleting a line answers 204 with no body")
    void deleteItemAnswers204() throws Exception {
        mvc.perform(delete("/api/v1/quotations/107/items/500"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(quotationService).deleteItem(107L, 500L);
    }

    // ── OMS ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the OMS answer is handed back as it arrived")
    void sendToOmsReturnsRawAnswer() throws Exception {
        when(quotationService.sendToOms(eq(107L), any())).thenReturn("{\"orderId\":\"SO-9001\"}");
        SendToOmsRequest req = new SendToOmsRequest();
        req.setSubmittedBy(1);
        req.setDeliveryWindows("a|b|c|4471");

        mvc.perform(post("/api/v1/quotations/107/send-to-oms")
                        .contentType(MediaType.APPLICATION_JSON).content(body(req)))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"orderId\":\"SO-9001\"}"));
    }

    @Test
    @DisplayName("the OMS status history comes back as it was parsed")
    void omsStatusReturnsHistory() throws Exception {
        when(quotationService.getOmsStatus(107L)).thenReturn(Map.of("status", "DELIVERED"));

        mvc.perform(get("/api/v1/quotations/107/oms-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    // ── Bank transfer notice ──────────────────────────────────────────────

    @Test
    @DisplayName("the bank-transfer notice returns the figures, not a sentence")
    void bankTransferNoticeReturnsFigures() throws Exception {
        when(bankTransferNoticeService.check(107L, "110")).thenReturn(
                new BankTransferNoticeResponse(true, false,
                        new BigDecimal("150000"), new BigDecimal("500000"), "COP"));

        mvc.perform(get("/api/v1/quotations/107/bank-transfer-notice").param("paymentMethod", "110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applies").value(true))
                .andExpect(jsonPath("$.valid").value(false))
                // The wording is composed in the frontend so both locales read right.
                .andExpect(jsonPath("$.currency").value("COP"));
    }

    @Test
    @DisplayName("the notice cannot be asked for without naming a tender")
    void bankTransferNoticeRequiresPaymentMethod() throws Exception {
        mvc.perform(get("/api/v1/quotations/107/bank-transfer-notice"))
                .andExpect(status().isBadRequest());
    }
}
