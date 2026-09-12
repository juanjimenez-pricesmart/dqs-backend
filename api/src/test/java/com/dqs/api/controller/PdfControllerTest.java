package com.dqs.api.controller;

import com.dqs.api.exception.GlobalExceptionHandler;
import com.dqs.api.service.CallejasReportService;
import com.dqs.api.service.QuotePdfService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The quote PDF: an attachment named after the quotation, or a clean failure. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PdfControllerTest {

    @Mock private QuotePdfService quotePdfService;
    @Mock private CallejasReportService callejasReportService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PdfController(quotePdfService, callejasReportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the PDF downloads as an attachment named after the quotation")
    void pdfDownloadsAsAttachment() throws Exception {
        when(quotePdfService.generate(eq(107L), any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });

        mvc.perform(get("/api/v1/quotations/107/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"quote-107.pdf\""))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(new byte[] { '%', 'P', 'D', 'F' }));
    }

    @Test
    @DisplayName("a PDF that cannot be built is an empty 500, not a corrupt download")
    void pdfFailureIsAnEmptyServerError() throws Exception {
        when(quotePdfService.generate(anyLong(), any())).thenThrow(new RuntimeException("no template"));

        // A 200 with no bytes would save to disk and open as a broken file,
        // which reads to the operator as our bug rather than as a failure.
        mvc.perform(get("/api/v1/quotations/107/pdf"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("a quotation with no purchase order has no Callejas sheet — 404, not 500")
    void noPurchaseOrderIsNotFound() throws Exception {
        when(callejasReportService.generate(anyLong()))
                .thenThrow(new com.dqs.api.exception.QuotationNotFoundException(107L));

        // The button is only offered for a Callejas import, so this is a stale
        // page rather than an outage; a 500 would read as one and be retried.
        mvc.perform(get("/api/v1/quotations/107/callejas-report"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the picking sheet downloads as its own attachment")
    void callejasReportDownloads() throws Exception {
        when(callejasReportService.generate(107L)).thenReturn(new byte[] { '%', 'P', 'D', 'F' });

        mvc.perform(get("/api/v1/quotations/107/callejas-report"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"callejas-107.pdf\""))
                .andExpect(content().contentType("application/pdf"));
    }

    @Test
    @DisplayName("the sort the operator picked reaches the PDF")
    void sortByReachesTheService() throws Exception {
        when(quotePdfService.generate(eq(107L), any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });

        mvc.perform(get("/api/v1/quotations/107/pdf?sortBy=4")).andExpect(status().isOk());

        verify(quotePdfService).generate(107L, 4);
    }

    @Test
    @DisplayName("no sortBy at all is passed through as null, for the service to default")
    void anAbsentSortIsNull() throws Exception {
        // Legacy's default is department, not code, and deciding that here
        // would put the rule in two places.
        when(quotePdfService.generate(eq(107L), any())).thenReturn(new byte[] { '%', 'P', 'D', 'F' });

        mvc.perform(get("/api/v1/quotations/107/pdf")).andExpect(status().isOk());

        verify(quotePdfService).generate(107L, null);
    }
}
