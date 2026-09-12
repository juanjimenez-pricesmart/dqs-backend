package com.dqs.api.controller;

import com.dqs.api.dto.QuotationDocumentResponse;
import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.exception.VoucherUploadException;
import com.dqs.api.service.QuotationDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Attaching the payment voucher, and reading back what is attached. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationDocumentControllerTest {

    @Mock private QuotationDocumentService documentService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new QuotationDocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "recibo.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    @DisplayName("a stored voucher answers 201 with where it landed")
    void uploadAnswers201() throws Exception {
        when(documentService.uploadVoucher(eq(107L), any(), any())).thenReturn(
                QuotationDocumentResponse.builder()
                        .id(900L).quotationId(107L).documentType("PROOF_OF_PAYMENT")
                        .fileName("recibo.pdf").storageUrl("https://b.s3.us-east-1.amazonaws.com/k.pdf")
                        .build());

        mvc.perform(multipart("/api/v1/quotations/107/vouchers").file(pdf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("recibo.pdf"))
                .andExpect(jsonPath("$.storageUrl").exists());
    }

    @Test
    @DisplayName("a file the voucher does not accept answers 400, not 500")
    void aRejectedFileIsABadRequest() throws Exception {
        when(documentService.uploadVoucher(eq(107L), any(), any()))
                .thenThrow(new VoucherUploadException("Only JPG, GIF, PNG and PDF files can be attached"));

        // The operator picked the wrong file; that is not an outage, and the
        // screen has to be able to say what was wrong with it.
        mvc.perform(multipart("/api/v1/quotations/107/vouchers").file(pdf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("the vouchers already attached come back as a list")
    void listingAnswersTheAttachedVouchers() throws Exception {
        when(documentService.listVouchers(107L)).thenReturn(List.of(
                QuotationDocumentResponse.builder().id(900L).fileName("recibo.pdf").build(),
                QuotationDocumentResponse.builder().id(901L).fileName("segundo.pdf").build()));

        mvc.perform(get("/api/v1/quotations/107/vouchers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("recibo.pdf"))
                .andExpect(jsonPath("$[1].fileName").value("segundo.pdf"));
    }

    @Test
    @DisplayName("a quotation with nothing attached answers an empty list")
    void nothingAttachedIsAnEmptyList() throws Exception {
        when(documentService.listVouchers(107L)).thenReturn(List.of());

        // The screen reads the length to decide whether the close gate is
        // satisfied, so this must not be a 404.
        mvc.perform(get("/api/v1/quotations/107/vouchers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
