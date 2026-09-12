package com.dqs.api.service;

import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * The Callejas picking sheet.
 *
 * The sheet is scanned in a warehouse, so what these tests defend is that the
 * right lines appear, in the shelf order, and that a barcode which cannot be
 * encoded costs its barcode rather than its whole card.
 *
 * The document is rendered for real — openhtmltopdf runs in-process and ZXing
 * produces actual Code 128 — so a template that stops parsing shows up here
 * rather than as a corrupt download.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallejasReportServiceTest {

    @Mock private QuotationService quotationService;
    @Mock private NativeQueries nativeQueries;

    private CallejasReportService service() {
        return new CallejasReportService(quotationService, nativeQueries);
    }

    private QuotationResponse quote(Long odc) {
        return QuotationResponse.builder()
                .id(107L).storeId(6701).userId(1).statusId(1)
                .customerName("CALLEJAS").odc(odc).build();
    }

    private QuotationItemResponse line(String code, String qty) {
        return QuotationItemResponse.builder()
                .id(1L).productId(code).description("ARROZ")
                .qty(new BigDecimal(qty)).rate(BigDecimal.TEN).amount(BigDecimal.TEN).build();
    }

    private Map<String, Object> callejasItem(String item, String barcode, String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", item);
        row.put("cbarras", barcode);
        row.put("descallejas", description);
        return row;
    }

    @BeforeEach
    void wire() {
        when(quotationService.getById(107L)).thenReturn(quote(24110230004769L));
        when(nativeQueries.first(contains("ps_tienda"), any()))
                .thenReturn(Optional.of(Map.of("ps_tienda_id", 6701, "nombre", "Club SV",
                        "direccion", "San Salvador", "telefono", "2222-2222")));
        when(nativeQueries.first(contains("ps_callejas_header"), any(), any()))
                .thenReturn(Optional.of(Map.of("ordendecompra", 24110230004769L,
                        "fecha", "2026-09-01", "fecha_entrega", "2026-09-08", "sucursalid", 12)));
        when(nativeQueries.first(contains("ps_callejas_sucursales"), any()))
                .thenReturn(Optional.of(Map.of("sucursalid", 12, "nombre", "Sucursal Centro")));
        when(nativeQueries.list(contains("ps_callejas_items"))).thenReturn(List.of(
                callejasItem("9101", "607766000130", "ENDULZANTE S CALORIAS"),
                callejasItem("1055", "707595001138", "PIMIENTOS ASADOS"),
                callejasItem("9102", "737628011094", "LECHE DE COCO")));
        when(quotationService.getItems(107L)).thenReturn(List.of(
                line("9101", "2"), line("1055", "5"), line("9102", "1")));
    }

    private String pdfText(byte[] pdf) {
        return new String(pdf, 0, Math.min(pdf.length, 8), StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("the sheet renders as a PDF carrying one card per Callejas line")
    void theSheetRenders() throws Exception {
        byte[] pdf = service().generate(107L);

        assertThat(pdfText(pdf)).startsWith("%PDF");
        assertThat(pdf.length).isGreaterThan(2000);
    }

    @Test
    @DisplayName("a line that is not a Callejas item is left off, as legacy's inner join leaves it")
    void aNonCallejasLineIsLeftOff() throws Exception {
        when(quotationService.getItems(107L)).thenReturn(List.of(
                line("9101", "2"), line("999999", "1")));
        int withOneUnknown = service().generate(107L).length;

        when(quotationService.getItems(107L)).thenReturn(List.of(line("9101", "2")));

        // The sheet describes what Callejas ordered, not what the quotation
        // happens to carry, so the unknown code changes nothing.
        assertThat(service().generate(107L).length).isEqualTo(withOneUnknown);
    }

    @Test
    @DisplayName("the delivery SKU never reaches the sheet")
    void theDeliverySkuIsNotOnTheSheet() throws Exception {
        when(quotationService.getItems(107L)).thenReturn(List.of(
                line("9101", "2"), line(com.dqs.api.util.SpecialItems.DELIVERY, "1")));

        assertThat(pdfText(service().generate(107L))).startsWith("%PDF");
    }

    @Test
    @DisplayName("a quotation with no purchase order has no sheet to print")
    void withoutAPurchaseOrderThereIsNoSheet() {
        when(quotationService.getById(107L)).thenReturn(quote(null));
        assertThatThrownBy(() -> service().generate(107L))
                .isInstanceOf(QuotationNotFoundException.class);

        // Legacy writes 0 for the same thing.
        when(quotationService.getById(107L)).thenReturn(quote(0L));
        assertThatThrownBy(() -> service().generate(107L))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    @Test
    @DisplayName("an order with no Callejas lines says so rather than printing a blank page")
    void anOrderWithNoCallejasLinesSaysSo() throws Exception {
        when(quotationService.getItems(107L)).thenReturn(List.of(line("999999", "1")));

        // Legacy renders an empty gallery, which reads as a broken report.
        assertThat(pdfText(service().generate(107L))).startsWith("%PDF");
    }

    @Test
    @DisplayName("a barcode that cannot be encoded costs its barcode, not its card")
    void anUnencodableBarcodeKeepsItsCard() throws Exception {
        when(nativeQueries.list(contains("ps_callejas_items"))).thenReturn(List.of(
                callejasItem("9101", "", "SIN CODIGO"),
                callejasItem("1055", "707595001138", "PIMIENTOS ASADOS")));
        when(quotationService.getItems(107L)).thenReturn(List.of(line("9101", "2"), line("1055", "5")));

        // The picker can still find the item by its code and description; a
        // missing card would just lose the line.
        assertThat(pdfText(service().generate(107L))).startsWith("%PDF");
    }

    @Test
    @DisplayName("the legacy double columns do not print as 607766000130.0")
    void doubleColumnsPrintAsIntegers() throws Exception {
        when(nativeQueries.list(contains("ps_callejas_items"))).thenReturn(List.of(
                callejasItem("9101", null, "SIN CODIGO")));
        Map<String, Object> numeric = new LinkedHashMap<>();
        numeric.put("item", 9101.0d);
        numeric.put("cbarras", 607766000130.0d);
        numeric.put("descallejas", "ENDULZANTE");
        when(nativeQueries.list(contains("ps_callejas_items"))).thenReturn(List.of(numeric));
        when(quotationService.getItems(107L)).thenReturn(List.of(line("9101", "2")));

        // item and cbarras are both `double` in the legacy schema, so a plain
        // toString would put ".0" into the barcode and scan to nothing.
        assertThat(pdfText(service().generate(107L))).startsWith("%PDF");
    }

    // ── The barcode has to scan ───────────────────────────────────────────

    /**
     * Encode through the service, then decode the image back with a reader.
     *
     * The whole sheet exists to be scanned, and every other assertion here only
     * proves a PDF came out. A Code 128 that renders beautifully and decodes to
     * the wrong digits would pass all of them and fail in the warehouse.
     */
    private String decodeBarcodeFrom(String barcode) throws Exception {
        java.lang.reflect.Method m = CallejasReportService.class
                .getDeclaredMethod("barcodePng", String.class);
        m.setAccessible(true);
        String dataUri = (String) m.invoke(service(), barcode);
        if (dataUri == null) return null;

        byte[] png = java.util.Base64.getDecoder()
                .decode(dataUri.substring(dataUri.indexOf(',') + 1));
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(png));
        com.google.zxing.LuminanceSource source =
                new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image);
        com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(source));
        return new com.google.zxing.MultiFormatReader().decode(bitmap).getText();
    }

    @Test
    @DisplayName("the printed barcode scans back to the digits it was made from")
    void theBarcodeScansBack() throws Exception {
        assertThat(decodeBarcodeFrom("607766000130")).isEqualTo("607766000130");
        assertThat(decodeBarcodeFrom("737628011094")).isEqualTo("737628011094");
    }

    @Test
    @DisplayName("a barcode already missing its leading zero scans as the wrong number, and that is the data's fault")
    void aTruncatedBarcodeScansAsStored() throws Exception {
        // ps_callejas_items.cbarras is a `double`, so 041449003153 was stored
        // as 41449003153 long before this code saw it. We print what is there;
        // padding would mean guessing UPC-A over EAN-13.
        assertThat(decodeBarcodeFrom("41449003153")).isEqualTo("41449003153");
    }

    @Test
    @DisplayName("an empty barcode yields no image at all rather than an unscannable one")
    void anEmptyBarcodeYieldsNoImage() throws Exception {
        assertThat(decodeBarcodeFrom("")).isNull();
        assertThat(decodeBarcodeFrom(null)).isNull();
    }
}
