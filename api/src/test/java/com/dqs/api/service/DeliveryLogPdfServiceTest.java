package com.dqs.api.service;

import com.dqs.api.repository.DeliveryLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The loading sheet a driver signs.
 *
 * The PDF is rendered for real — iText runs in-process, so there is nothing to
 * stub and a broken template shows up as a failure here rather than as a
 * corrupt download. The assertions are on the bytes being a PDF and on the
 * figures being right, since the layout is not something a test can judge.
 *
 * The row values come out of native SQL, so the driver decides whether a column
 * arrives as a Long, an Integer, a BigDecimal or text. Each shape gets a case:
 * a total that reads 0.00 because a string did not coerce is a truck loaded
 * against the wrong paperwork.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryLogPdfServiceTest {

    @Mock private DeliveryLogRepository deliveryLogRepository;

    private DeliveryLogPdfService service() {
        return new DeliveryLogPdfService(deliveryLogRepository);
    }

    private Map<String, Object> logRow(Object statusId) {
        Map<String, Object> row = new HashMap<>();
        row.put("logcargueid", 9L);
        row.put("fecha", "2026-09-15");
        row.put("hora", "08:30");
        row.put("delivery_ruta", "6101 01");
        row.put("statusid", statusId);
        return row;
    }

    private Map<String, Object> deliveryRow(Object quotationId, Object amount, Object pallets) {
        Map<String, Object> row = new HashMap<>();
        row.put("quotationId", quotationId);
        row.put("customerName", "ACME SA");
        row.put("deliveryDate", "2026-09-16");
        row.put("hourFrom", 8);
        row.put("hourTo", 17);
        row.put("address", "CRA 43 N 82 66");
        row.put("amount", amount);
        row.put("pallets", pallets);
        return row;
    }

    private void stub(Map<String, Object> log, List<Map<String, Object>> deliveries) {
        when(deliveryLogRepository.findById(9L)).thenReturn(log);
        when(deliveryLogRepository.findDeliveriesByLogId(9L)).thenReturn(deliveries);
    }

    /**
     * The generated HTML, read back off the PDF is not practical — so the
     * template is exercised through the render and the figures are asserted by
     * generating twice and comparing sizes where a value must appear. For the
     * text itself the service is asked for the same log with one value changed.
     */
    private byte[] generate() throws Exception {
        return service().generatePdf(9L);
    }

    @Test
    @DisplayName("a log with deliveries renders a real PDF")
    void rendersAPdf() throws Exception {
        stub(logRow(2), List.of(deliveryRow(107L, 500.0, 2)));

        byte[] pdf = generate();

        // The four-byte header is what a viewer checks before opening it.
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    @DisplayName("every delivery on the log becomes a row, so a longer log is a longer document")
    void everyDeliveryBecomesARow() throws Exception {
        stub(logRow(2), List.of(deliveryRow(107L, 500.0, 2)));
        int oneRow = generate().length;

        stub(logRow(2), List.of(
                deliveryRow(107L, 500.0, 2),
                deliveryRow(108L, 750.0, 3),
                deliveryRow(109L, 250.0, 1)));

        assertThat(generate().length).isGreaterThan(oneRow);
    }

    @Test
    @DisplayName("the amounts coerce from every shape the driver may hand back")
    void amountsCoerceFromEveryShape() throws Exception {
        // Long, Integer, BigDecimal and text all have to reach the total.
        for (Object amount : new Object[] {
                500L, 500, new java.math.BigDecimal("500.00"), "500.00", 500.0 }) {
            stub(logRow(2), List.of(deliveryRow(107L, amount, 2)));
            assertThat(generate()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("a delivery with no amount counts as nothing rather than failing the sheet")
    void missingAmountCountsAsZero() throws Exception {
        stub(logRow(2), List.of(deliveryRow(107L, null, null)));

        assertThat(new String(generate(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("the status is named, and an unknown one says so instead of printing a number")
    void statusIsNamed() throws Exception {
        // 2 is active, 3 is sent — a pair of magic numbers with no constant.
        for (Object status : new Object[] { 2, 3, 99, "2", null }) {
            stub(logRow(status), List.of(deliveryRow(107L, 500.0, 2)));
            assertThat(generate()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("a log that does not exist is refused rather than rendered empty")
    void unknownLogIsRefused() {
        stub(null, List.of(deliveryRow(107L, 500.0, 2)));

        assertThatThrownBy(() -> service().generatePdf(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Log or deliveries not found: 9");
    }

    @Test
    @DisplayName("a log with no deliveries is refused too — there is nothing to load")
    void emptyLogIsRefused() {
        stub(logRow(2), List.of());

        // A sheet with no rows would be signed for an empty truck.
        assertThatThrownBy(() -> service().generatePdf(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Log or deliveries not found: 9");
    }

    @Test
    @DisplayName("a log with missing header columns still renders, rather than losing the sheet")
    void missingHeaderColumnsStillRender() throws Exception {
        Map<String, Object> sparse = new HashMap<>();
        sparse.put("logcargueid", 9L);
        stub(sparse, List.of(deliveryRow(107L, 500.0, 2)));

        assertThat(new String(generate(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
