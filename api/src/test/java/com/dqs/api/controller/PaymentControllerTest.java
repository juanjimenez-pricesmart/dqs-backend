package com.dqs.api.controller;

import com.dqs.api.catalog.source.CatalogSource;
import com.dqs.api.catalog.source.PaymentMethodInfo;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.PriceSmartPaymentService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment endpoints.
 *
 * The payment request carries the caller's IP because the payments service
 * requires it for fraud scoring, and the controller reads it off the request
 * rather than trusting a field in the body — which is the part worth a test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentControllerTest {

    @Mock private PriceSmartPaymentService paymentService;
    @Mock private CatalogSource catalogSource;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService, catalogSource))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the payment methods of a country come from the catalog source, whichever is switched on")
    void methodsComeFromTheCatalogSource() throws Exception {
        when(catalogSource.paymentMethodsOfCountry("CO"))
                .thenReturn(List.of(new PaymentMethodInfo(1, "Transferencia", 110)));

        mvc.perform(get("/api/v1/payments/methods").param("country", "CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenderKey").value(110))
                .andExpect(jsonPath("$[0].description").value("Transferencia"));
    }

    @Test
    @DisplayName("methods cannot be asked for without a country")
    void methodsRequireCountry() throws Exception {
        mvc.perform(get("/api/v1/payments/methods")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogSource);
    }

    @Test
    @DisplayName("a payment request carries the caller's own IP, not one it was handed")
    void requestUsesTheCallersIp() throws Exception {
        when(paymentService.createPaymentRequest(anyLong(), anyString()))
                .thenReturn(Map.of("authorizationToken", "tok", "iframeUrl", "https://pay/x"));

        mvc.perform(post("/api/v1/payments/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        // An ipAddress in the body is ignored on purpose: the
                        // payments service scores fraud on it.
                        .content("{\"quotationId\":107,\"ipAddress\":\"1.2.3.4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iframeUrl").value("https://pay/x"));

        verify(paymentService).createPaymentRequest(eq(107L), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("a quotation id sent as text is still read as a number")
    void requestParsesTextualQuotationId() throws Exception {
        when(paymentService.createPaymentRequest(anyLong(), anyString())).thenReturn(Map.of());

        mvc.perform(post("/api/v1/payments/request")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quotationId\":\"107\"}"))
                .andExpect(status().isOk());

        verify(paymentService).createPaymentRequest(eq(107L), anyString());
    }

    @Test
    @DisplayName("a payment status is proxied by invoice id")
    void statusIsProxied() throws Exception {
        when(paymentService.getPaymentStatus("INV-1")).thenReturn(Map.of("status_code", "AUTHORIZED"));

        mvc.perform(get("/api/v1/payments/status").param("invoiceId", "INV-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status_code").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("a status request without an invoice is refused")
    void statusRequiresInvoiceId() throws Exception {
        mvc.perform(get("/api/v1/payments/status")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the webhook accepts a body and answers empty, so the payments service stops retrying")
    void callbackAcknowledgesABody() throws Exception {
        mvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"INV-1\",\"status\":\"AUTHORIZED\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("and accepts a callback with no body at all, which is how some retries arrive")
    void callbackAcceptsNoBody() throws Exception {
        mvc.perform(post("/api/v1/payments/callback").param("invoiceId", "INV-1"))
                .andExpect(status().isOk());
    }
}
