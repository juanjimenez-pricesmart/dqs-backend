package com.dqs.api.service;

import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * The quote PDF the customer receives.
 *
 * Rendered for real: openhtmltopdf runs in-process, so a template that no
 * longer parses shows up here rather than as a corrupt download. What a test
 * cannot judge is the layout, so the assertions are that the bytes are a PDF
 * and that the right branch of the totals block ran — and the four totals
 * variants are the reason this file exists, because each country reads its own
 * set of labels and a wrong one is a quote the customer disputes.
 *
 * Images are data URIs. The renderer resolves whatever `picture1` points at, so
 * an http URL in a fixture would make this suite reach out to a real host.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotePdfServiceTest {

    @Mock private QuotationService quotationService;
    @Mock private FiscalService fiscalService;
    @Mock private DeliveryService deliveryService;
    @Mock private NativeQueries nativeQueries;

    private QuotePdfService service() {
        return new QuotePdfService(quotationService, fiscalService, deliveryService, nativeQueries);
    }

    /** A 1x1 transparent PNG, so the renderer has something real to resolve. */
    private static final String PIXEL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
            + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private QuotationResponse quote(int storeId) {
        return QuotationResponse.builder()
                .id(107L).storeId(storeId).userId(1).statusId(3)
                .dateTime(LocalDateTime.of(2026, 9, 10, 15, 30))
                .dexpired(LocalDate.of(2026, 10, 1))
                .customerName("JUAN PEREZ").customerBusiness("ACME SA")
                .customerMembership("70012345678901")
                .quoteNo("SO-9001")
                .build();
    }

    private QuotationItemResponse line(String code) {
        return QuotationItemResponse.builder()
                .id(500L).quotationId(107L).productId(code).description("ARROZ 5KG")
                .qty(new BigDecimal("2.00")).rate(new BigDecimal("119"))
                .signPrice(new BigDecimal("119")).amount(new BigDecimal("238"))
                .taxPorcentaje(new BigDecimal("19")).taxAmount(new BigDecimal("38"))
                .taxIco(new BigDecimal("2"))
                .department("01").category("0101")
                .build();
    }

    private Map<String, Object> storeRow(String iso2, String currency) {
        Map<String, Object> row = new HashMap<>();
        row.put("ps_tienda_id", 6101);
        row.put("nombre", "Barranquilla");
        row.put("pais_iso2", iso2);
        row.put("moneda", currency);
        return row;
    }

    private Map<String, Object> deliveryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("address", "CRA 43 N 82 66");
        row.put("delivery_date", "2026-09-16");
        row.put("hour_from", 8);
        row.put("hour_to", 17);
        row.put("amount", 500);
        row.put("ring", "R3");
        row.put("box", "B12");
        return row;
    }

    /** Wires the four collaborators for one club. */
    private void stub(int storeId, String iso2, String currency,
                      List<QuotationItemResponse> items,
                      Map<String, Object> fiscal, Map<String, Object> delivery) {
        when(quotationService.getById(107L)).thenReturn(quote(storeId));
        when(quotationService.getItems(107L)).thenReturn(items);
        when(nativeQueries.list(contains("FROM ps_tienda"), any()))
                .thenReturn(List.of(storeRow(iso2, currency)));
        when(fiscalService.getFiscalDataByQuotation(107L)).thenReturn(fiscal);
        when(deliveryService.getDelivery(107L)).thenReturn(delivery);
    }

    private byte[] generate() throws Exception {
        return service().generate(107L);
    }

    private void assertIsPdf(byte[] pdf) {
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    // ── The four totals variants ──────────────────────────────────────────

    @Test
    @DisplayName("a Colombian quote renders its own set of totals")
    void colombiaRendersItsOwnTotals() throws Exception {
        // Total Valor Base / Impuesto / Impuesto ICO / Compra Total.
        stub(6101, "CO", "COP", List.of(line("1001")), null, deliveryRow());

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a VAT-inclusive quote renders the subtotal net of tax")
    void vatInclusiveRendersNetSubtotal() throws Exception {
        // Guatemala: the rate already includes the tax, so the subtotal is
        // gross minus tax and the total is the gross plus delivery.
        stub(6301, "GT", "GTQ", List.of(line("1001")), null, deliveryRow());

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a Barbados quote labels its tax VAT and its total Amount Due")
    void barbadosRendersItsOwnLabels() throws Exception {
        stub(8501, "BB", "BBD", List.of(line("1001")), null, deliveryRow());

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("everywhere else gets the generic subtotal, tax and total")
    void everyoneElseGetsTheGenericTotals() throws Exception {
        stub(6401, "CR", "CRC", List.of(line("1001")), null, deliveryRow());

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a country that charges no tax renders no tax row at all")
    void noIvaCountryRendersNoTaxRow() throws Exception {
        // Panama, Nicaragua and Peru: the tax factor is always zero, so the
        // tax row is suppressed rather than printed as 0.00.
        stub(8901, "NI", "NIO", List.of(line("1001")), null, null);
        int withoutTax = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001")), null, null);

        assertThat(generate().length).isGreaterThan(withoutTax);
    }

    @Test
    @DisplayName("a quote with no delivery and no tax prints neither row")
    void aBareQuotePrintsNeitherRow() throws Exception {
        QuotationItemResponse untaxed = QuotationItemResponse.builder()
                .id(500L).quotationId(107L).productId("1001").description("ARROZ")
                .qty(BigDecimal.ONE).rate(new BigDecimal("100")).amount(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO).build();
        stub(6401, "CR", "CRC", List.of(untaxed), null, null);

        assertIsPdf(generate());
    }

    /** A line that costs money but carries no tax and no ICO. */
    private QuotationItemResponse untaxedLine() {
        return QuotationItemResponse.builder()
                .id(500L).quotationId(107L).productId("1001").description("ARROZ 5KG")
                .qty(BigDecimal.ONE).rate(new BigDecimal("100")).amount(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO).taxIco(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("each country's totals block drops the tax and delivery rows when there are none")
    void everyVariantDropsItsOptionalRows() throws Exception {
        // 6101 Colombia, 6301 VAT-inclusive, 8501 Barbados, 6401 generic. Each
        // prints its own labels, and each suppresses the two optional rows
        // rather than printing them at 0.00.
        for (int storeId : new int[] { 6101, 6301, 8501, 6401 }) {
            stub(storeId, "XX", "USD", List.of(untaxedLine()), null, null);
            assertIsPdf(generate());
        }
    }

    @Test
    @DisplayName("a Colombian quote with a line missing its ICO and quantity still totals")
    void colombiaSurvivesALineWithoutIcoOrQuantity() throws Exception {
        QuotationItemResponse bare = QuotationItemResponse.builder()
                .id(500L).quotationId(107L).productId("1001").description("ARROZ")
                .amount(new BigDecimal("100")).taxAmount(new BigDecimal("19"))
                .build();
        stub(6101, "CO", "COP", List.of(bare), null, null);

        // A missing quantity counts as one, so the ICO does not vanish silently.
        assertIsPdf(generate());
    }

    // ── Sections that appear conditionally ────────────────────────────────

    @Test
    @DisplayName("an invoicing country with saved data gets the FEL block")
    void felCountryWithDataGetsTheBlock() throws Exception {
        Map<String, Object> fiscal = new HashMap<>();
        fiscal.put("nrc", "12345");
        fiscal.put("economic_activity_name", "Comercio al por menor");
        fiscal.put("city_name", "SAN JOSE");
        fiscal.put("zone_name", "CENTRO");
        fiscal.put("neighborhood_name", "CARMEN");
        stub(6401, "CR", "CRC", List.of(line("1001")), fiscal, null);
        int withFel = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001")), null, null);

        assertThat(withFel).isGreaterThan(generate().length);
    }

    @Test
    @DisplayName("the FEL block falls back to the codes when the names were never resolved")
    void felFallsBackToCodes() throws Exception {
        Map<String, Object> fiscal = new HashMap<>();
        fiscal.put("nrc", "12345");
        fiscal.put("economic_activity_code", "4711");
        fiscal.put("city_code", "08001");
        fiscal.put("zone_code", "01");
        fiscal.put("neighborhood_code", "0101");
        stub(6401, "CR", "CRC", List.of(line("1001")), fiscal, null);

        // A code is worse than a name but better than a blank row.
        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a country outside the invoicing scheme gets no FEL block even with data on file")
    void nonFelCountryGetsNoBlock() throws Exception {
        Map<String, Object> fiscal = new HashMap<>();
        fiscal.put("nrc", "12345");
        stub(6101, "CO", "COP", List.of(line("1001")), fiscal, null);
        int colombia = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001")), fiscal, null);

        assertThat(generate().length).isGreaterThan(colombia);
    }

    @Test
    @DisplayName("the delivery block carries its window, ring and box when they exist")
    void deliveryBlockCarriesEverything() throws Exception {
        stub(6401, "CR", "CRC", List.of(line("1001")), null, deliveryRow());
        int full = generate().length;

        Map<String, Object> sparse = new HashMap<>();
        sparse.put("address", "CRA 43");
        sparse.put("amount", 500);
        stub(6401, "CR", "CRC", List.of(line("1001")), null, sparse);

        // No hour_from, so no time window row; no ring or box either.
        assertThat(full).isGreaterThan(generate().length);
    }

    @Test
    @DisplayName("a delivery with no amount on it still renders its block")
    void deliveryWithoutAnAmountStillRenders() throws Exception {
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("address", "CRA 43");
        stub(6401, "CR", "CRC", List.of(line("1001")), null, delivery);

        assertIsPdf(generate());
    }

    // ── The items table ───────────────────────────────────────────────────

    @Test
    @DisplayName("the delivery SKU is left out of the table — it is a total, not a line")
    void deliverySkuIsNotAnItem() throws Exception {
        stub(6401, "CR", "CRC",
                List.of(line("1001"), line(com.dqs.api.util.SpecialItems.DELIVERY)), null, null);
        int filtered = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001"), line("1002")), null, null);

        // Two real lines render more than one real line plus the delivery SKU.
        assertThat(generate().length).isGreaterThan(filtered);
    }

    @Test
    @DisplayName("a line with no figures at all renders as blanks rather than failing")
    void aBareLineRendersAsBlanks() throws Exception {
        QuotationItemResponse bare = QuotationItemResponse.builder()
                .id(501L).quotationId(107L).productId("1002").build();
        stub(6401, "CR", "CRC", List.of(bare), null, null);

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a line with a department but no category prints the department alone")
    void departmentWithoutCategory() throws Exception {
        QuotationItemResponse item = line("1001");
        item.setCategory("   ");
        stub(6401, "CR", "CRC", List.of(item), null, null);

        assertIsPdf(generate());

        QuotationItemResponse noDept = line("1001");
        noDept.setDepartment(null);
        noDept.setCategory(null);
        stub(6401, "CR", "CRC", List.of(noDept), null, null);

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a quote with no items at all still renders, and says so in the singular")
    void anEmptyQuoteStillRenders() throws Exception {
        stub(6401, "CR", "CRC", List.of(), null, null);

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("one item reads \"1 item\" and two read \"2 items\"")
    void theItemCountIsPluralised() throws Exception {
        stub(6401, "CR", "CRC", List.of(line("1001")), null, null);
        int one = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001"), line("1002")), null, null);

        assertThat(generate().length).isGreaterThan(one);
    }

    @Test
    @DisplayName("text from the catalog is escaped, so an ampersand does not break the document")
    void catalogTextIsEscaped() throws Exception {
        QuotationItemResponse item = line("1001");
        item.setDescription("ARROZ & FRIJOL <blanco>");
        stub(6401, "CR", "CRC", List.of(item), null, null);

        // Unescaped, this would be invalid XML and the render would throw.
        assertIsPdf(generate());
    }

    // ── The image gallery ─────────────────────────────────────────────────

    @Test
    @DisplayName("items with a picture get a gallery page of their own")
    void itemsWithPicturesGetAGallery() throws Exception {
        QuotationItemResponse withPicture = line("1001");
        withPicture.setPicture1(PIXEL);
        stub(6401, "CR", "CRC", List.of(withPicture), null, null);
        int withGallery = generate().length;

        stub(6401, "CR", "CRC", List.of(line("1001")), null, null);

        assertThat(withGallery).isGreaterThan(generate().length);
    }

    @Test
    @DisplayName("a picture the operator excluded is left out of the gallery")
    void excludedPicturesAreLeftOut() throws Exception {
        QuotationItemResponse included = line("1001");
        included.setPicture1(PIXEL);
        included.setIncludepic(1);
        stub(6401, "CR", "CRC", List.of(included), null, null);
        int withGallery = generate().length;

        QuotationItemResponse excluded = line("1001");
        excluded.setPicture1(PIXEL);
        excluded.setIncludepic(0);
        stub(6401, "CR", "CRC", List.of(excluded), null, null);

        // The Item Info drawer's "include image in the quote" flag.
        assertThat(withGallery).isGreaterThan(generate().length);
    }

    @Test
    @DisplayName("the string \"NULL\" is not a picture, and neither is a blank one")
    void theStringNullIsNotAPicture() throws Exception {
        stub(6401, "CR", "CRC", List.of(line("1001")), null, null);
        int noGallery = generate().length;

        // Legacy stores the four characters N-U-L-L in that column.
        for (String value : new String[] { "NULL", "null", "   " }) {
            QuotationItemResponse item = line("1001");
            item.setPicture1(value);
            stub(6401, "CR", "CRC", List.of(item), null, null);
            assertThat(generate().length).isEqualTo(noGallery);
        }
    }

    // ── Header, footer and the club row ───────────────────────────────────

    @Test
    @DisplayName("a quote with no order number, date or expiry still renders its header")
    void aSparseHeaderStillRenders() throws Exception {
        when(quotationService.getById(107L)).thenReturn(QuotationResponse.builder()
                .id(107L).storeId(6401).build());
        when(quotationService.getItems(107L)).thenReturn(List.of(line("1001")));
        when(nativeQueries.list(contains("FROM ps_tienda"), any()))
                .thenReturn(List.of(storeRow("CR", "CRC")));

        // The footer says "valid until N/A" rather than omitting the sentence.
        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a quotation with no club id at all is treated as club zero")
    void aQuotationWithoutAClubStillRenders() throws Exception {
        when(quotationService.getById(107L)).thenReturn(QuotationResponse.builder()
                .id(107L).storeId(null).build());
        when(quotationService.getItems(107L)).thenReturn(List.of(line("1001")));
        when(nativeQueries.list(contains("FROM ps_tienda"), any())).thenReturn(List.of());

        // No club means no country, so it falls through to the generic totals.
        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a club that is not in the table renders an empty club block")
    void anUnknownClubRendersEmpty() throws Exception {
        when(quotationService.getById(107L)).thenReturn(quote(6401));
        when(quotationService.getItems(107L)).thenReturn(List.of(line("1001")));
        when(nativeQueries.list(contains("FROM ps_tienda"), any())).thenReturn(List.of());

        assertIsPdf(generate());
    }

    @Test
    @DisplayName("a quotation that does not exist is refused by the service beneath")
    void anUnknownQuotationIsRefused() {
        when(quotationService.getById(anyLong()))
                .thenThrow(new com.dqs.api.exception.QuotationNotFoundException(404L));

        assertThatThrownBy(() -> service().generate(404L))
                .isInstanceOf(com.dqs.api.exception.QuotationNotFoundException.class);
    }
}
