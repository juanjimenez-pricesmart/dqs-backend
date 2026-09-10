package com.dqs.api.service;

import com.dqs.api.dto.CloseQuotationRequest;
import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.QuotationItemRequest;
import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SendToOmsRequest;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationCustomer;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.model.QuotationItemProduct;
import com.dqs.api.model.QuotationItemTaxes;
import com.dqs.api.model.QuotationPayment;
import com.dqs.api.model.QuotationTotals;
import com.dqs.api.repository.QuotationCancelRepository;
import com.dqs.api.repository.QuotationItemRepository;
import com.dqs.api.repository.QuotationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quotation service, end to end over mocks.
 *
 * Two things shape this file. The OMS paths need an OAuth2 token, and the
 * service fetches it with its own HttpURLConnection rather than a client that
 * could be stubbed — so a JDK HttpServer stands in for the identity provider on
 * a loopback port. Nothing here reaches the network.
 *
 * And the arithmetic is asserted on the entity the repository was handed, not
 * on the response, because a figure that only reaches the DTO would still be
 * missing from the database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationServiceTest {

    @Mock private QuotationRepository quotationRepository;
    @Mock private QuotationItemRepository quotationItemRepository;
    @Mock private QuotationCancelRepository quotationCancelRepository;
    @Mock private OmsService omsService;
    @Mock private OmsPayloadBuilder omsPayloadBuilder;
    @Mock private ItemService itemService;
    @Mock private QuotationTotalsCalculator totalsCalculator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The stand-in identity provider, started only by the tests that need one. */
    private HttpServer idp;

    @AfterEach
    void stopIdp() {
        if (idp != null) {
            idp.stop(0);
            idp = null;
        }
    }

    private QuotationService service() {
        return service("http://localhost:1");
    }

    private QuotationService service(String idpBaseUrl) {
        QuotationService s = new QuotationService(quotationRepository, quotationItemRepository,
                quotationCancelRepository, omsService, omsPayloadBuilder, objectMapper,
                itemService, totalsCalculator);
        set(s, "idpBaseUrl", idpBaseUrl);
        set(s, "clientId", "quotecenter");
        set(s, "clientSecret", "s3cr3t");
        set(s, "dqsBaseUrl", "http://localhost/dqs");
        return s;
    }

    /**
     * The four @Value fields have no setters and no constructor slot, so they
     * are written directly. Spring does the same thing at startup.
     */
    private static void set(Object target, String field, Object value) {
        try {
            Field f = QuotationService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /** An identity provider that answers the token request with `body`. */
    private String startIdp(String body) {
        try {
            idp = HttpServer.create(new InetSocketAddress(0), 0);
            idp.createContext("/auth/realms/PriceSmart/protocol/openid-connect/token", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            idp.start();
            return "http://localhost:" + idp.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A port with nothing listening on it, for the token failure path. */
    private static String deadIdp() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Quotation quotation(int statusId) {
        Quotation q = Quotation.builder().id(107L).storeId(3).userId(1).statusId(statusId)
                .dexpired(LocalDate.of(2026, 10, 1)).build();
        q.setCustomer(QuotationCustomer.builder().quotation(q)
                .customerName("ACME").customerMembership("70012345").customerBusiness("ACME SA").build());
        q.setTotals(QuotationTotals.builder().quotation(q)
                .grossAmount(new BigDecimal("1000.00")).netAmount(new BigDecimal("1190.00"))
                .deliveryAmount(new BigDecimal("50.00")).excent(0).taxRate(new BigDecimal("19"))
                .aplicarImpuestos(1).discount(BigDecimal.ZERO).vatChargeRate(BigDecimal.ZERO)
                .vatCharge(BigDecimal.ZERO).serviceChargeRate(BigDecimal.ZERO)
                .serviceCharge(BigDecimal.ZERO).build());
        return q;
    }

    private QuotationItem item(Quotation q, Long id, String code) {
        QuotationItem it = QuotationItem.builder().id(id).quotation(q).productId(code)
                .qty(new BigDecimal("2")).rate(new BigDecimal("100"))
                .signPrice(new BigDecimal("100")).amount(new BigDecimal("200"))
                .icomments("").includepic(0).variacion(0).build();
        it.setTaxes(QuotationItemTaxes.builder().item(it)
                .taxPorcentaje(new BigDecimal("19")).taxFactor(new BigDecimal("19"))
                .taxAmount(new BigDecimal("38")).taxIco(BigDecimal.ZERO)
                .excentPorcentaje(BigDecimal.ZERO).excentAmount(BigDecimal.ZERO).build());
        it.setProduct(QuotationItemProduct.builder().item(it)
                .description("ARROZ 5KG").pl(new BigDecimal("10")).weightEa(new BigDecimal("1.5"))
                .onhand(new BigDecimal("40")).build());
        return it;
    }

    private void repoReturnsSelf() {
        when(quotationRepository.save(any(Quotation.class))).thenAnswer(i -> i.getArgument(0));
        when(quotationItemRepository.save(any(QuotationItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CreateQuotationRequest createRequest() {
        CreateQuotationRequest req = new CreateQuotationRequest();
        req.setCustomerName("ACME");
        req.setCustomerMembership("70012345");
        req.setCustomerBusiness("ACME SA");
        req.setStoreId(3);
        req.setUserId(1);
        req.setTaxRate(new BigDecimal("19"));
        req.setGrossAmount(new BigDecimal("1000"));
        req.setNetAmount(new BigDecimal("1190"));
        return req;
    }

    private Quotation captureSaved() {
        ArgumentCaptor<Quotation> captor = ArgumentCaptor.forClass(Quotation.class);
        verify(quotationRepository).save(captor.capture());
        return captor.getValue();
    }

    private QuotationItem captureSavedItem() {
        ArgumentCaptor<QuotationItem> captor = ArgumentCaptor.forClass(QuotationItem.class);
        verify(quotationItemRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── createQuotation ───────────────────────────────────────────────────

    @Test
    @DisplayName("a new quotation is born pending, with its customer and totals attached")
    void createsPendingQuotation() {
        repoReturnsSelf();

        QuotationResponse response = service().createQuotation(createRequest());

        Quotation saved = captureSaved();
        assertThat(saved.getStatusId()).isEqualTo(1);
        assertThat(saved.getStoreId()).isEqualTo(3);
        assertThat(saved.getCustomer().getCustomerName()).isEqualTo("ACME");
        assertThat(saved.getCustomer().getQuotation()).isSameAs(saved);
        assertThat(saved.getTotals().getQuotation()).isSameAs(saved);
        // Nothing is exempt and nothing is being delivered yet.
        assertThat(saved.getTotals().getExcent()).isZero();
        assertThat(saved.getTotals().getDeliveryAmount()).isEqualByComparingTo("0");
        assertThat(response.getStatusId()).isEqualTo(1);
        assertThat(response.getCustomerMembership()).isEqualTo("70012345");
    }

    @Test
    @DisplayName("no payment row is created until the quote is submitted or closed")
    void createLeavesPaymentUnset() {
        repoReturnsSelf();

        QuotationResponse response = service().createQuotation(createRequest());

        assertThat(captureSaved().getPayment()).isNull();
        assertThat(response.getQuoteNo()).isNull();
        assertThat(response.getQuoteTypeId()).isNull();
    }

    @Test
    @DisplayName("an expiry date sent by the client is honoured as given")
    void createHonoursExplicitExpiry() {
        repoReturnsSelf();
        CreateQuotationRequest req = createRequest();
        req.setDexpired("2026-12-24");

        service().createQuotation(req);

        assertThat(captureSaved().getDexpired()).isEqualTo(LocalDate.of(2026, 12, 24));
    }

    @Test
    @DisplayName("no expiry date means twenty-one days from today")
    void createDefaultsExpiryWhenAbsent() {
        repoReturnsSelf();

        service().createQuotation(createRequest());

        assertThat(captureSaved().getDexpired()).isEqualTo(LocalDate.now().plusDays(21));
    }

    @Test
    @DisplayName("a blank expiry date is treated as absent, not as an error")
    void createDefaultsExpiryWhenBlank() {
        repoReturnsSelf();
        CreateQuotationRequest req = createRequest();
        req.setDexpired("   ");

        service().createQuotation(req);

        assertThat(captureSaved().getDexpired()).isEqualTo(LocalDate.now().plusDays(21));
    }

    @Test
    @DisplayName("an unparseable expiry date falls back to the default instead of failing the create")
    void createDefaultsExpiryWhenUnparseable() {
        repoReturnsSelf();
        CreateQuotationRequest req = createRequest();
        req.setDexpired("24/12/2026");

        service().createQuotation(req);

        assertThat(captureSaved().getDexpired()).isEqualTo(LocalDate.now().plusDays(21));
    }

    // ── getById ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("reading a quotation maps its customer, totals and payment together")
    void getByIdMapsEveryBlock() {
        Quotation q = quotation(3);
        q.setPayment(QuotationPayment.builder().quotation(q).paidStatus(1).quoteTypeId(2)
                .paymentNumber("201888600").paymentMethodId("110").serviceId(0).quoteNo("SO-9001").build());
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));

        QuotationResponse response = service().getById(107L);

        assertThat(response.getId()).isEqualTo(107L);
        assertThat(response.getCustomerBusiness()).isEqualTo("ACME SA");
        assertThat(response.getNetAmount()).isEqualByComparingTo("1190.00");
        assertThat(response.getDeliveryAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getPaymentNumber()).isEqualTo("201888600");
        assertThat(response.getQuoteNo()).isEqualTo("SO-9001");
    }

    @Test
    @DisplayName("a quotation with no customer, totals or payment maps to a header-only response")
    void getByIdMapsBareQuotation() {
        when(quotationRepository.findById(9L)).thenReturn(
                Optional.of(Quotation.builder().id(9L).storeId(3).userId(1).statusId(1).build()));

        QuotationResponse response = service().getById(9L);

        assertThat(response.getId()).isEqualTo(9L);
        assertThat(response.getCustomerName()).isNull();
        assertThat(response.getNetAmount()).isNull();
        assertThat(response.getPaidStatus()).isNull();
    }

    @Test
    @DisplayName("an unknown id is reported as not found rather than returning an empty quote")
    void getByIdRejectsUnknownId() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(404L))
                .isInstanceOf(QuotationNotFoundException.class)
                .hasMessageContaining("404");
    }

    // ── submitQuotation ───────────────────────────────────────────────────

    @Test
    @DisplayName("submitting moves a pending quote to closed and records the tender")
    void submitMovesToClosed() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        repoReturnsSelf();
        SubmitQuotationRequest req = new SubmitQuotationRequest();
        req.setSubmittedBy(1);
        req.setPaymentMethodId(110);

        QuotationResponse response = service().submitQuotation(107L, req);

        assertThat(captureSaved().getStatusId()).isEqualTo(2);
        assertThat(response.getPaymentMethodId()).isEqualTo("110");
        // ensurePayment fills the columns the legacy row always carries.
        assertThat(response.getPaidStatus()).isEqualTo(1);
        assertThat(response.getServiceId()).isZero();
    }

    @Test
    @DisplayName("submitting without a tender stores \"0\" rather than a null column")
    void submitDefaultsPaymentMethod() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        repoReturnsSelf();
        SubmitQuotationRequest req = new SubmitQuotationRequest();
        req.setPaymentMethodId(null);

        assertThat(service().submitQuotation(107L, req).getPaymentMethodId()).isEqualTo("0");
    }

    @Test
    @DisplayName("submitting a quote that already has a payment row reuses it")
    void submitReusesExistingPayment() {
        Quotation q = quotation(1);
        QuotationPayment existing = QuotationPayment.builder().quotation(q).id(55L)
                .paidStatus(1).serviceId(0).paymentMethodId("0").build();
        q.setPayment(existing);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        repoReturnsSelf();
        SubmitQuotationRequest req = new SubmitQuotationRequest();
        req.setPaymentMethodId(120);

        service().submitQuotation(107L, req);

        assertThat(captureSaved().getPayment()).isSameAs(existing);
        assertThat(existing.getPaymentMethodId()).isEqualTo("120");
    }

    @Test
    @DisplayName("a quote that is no longer pending cannot be submitted again")
    void submitRejectsNonPending() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(3)));

        assertThatThrownBy(() -> service().submitQuotation(107L, new SubmitQuotationRequest()))
                .isInstanceOf(QuotationAlreadySubmittedException.class);
        verify(quotationRepository, never()).save(any(Quotation.class));
    }

    // ── saveItem ──────────────────────────────────────────────────────────

    private QuotationItemRequest itemRequest(String code) {
        QuotationItemRequest req = new QuotationItemRequest();
        req.setProductId(code);
        req.setDescription("ARROZ 5KG");
        req.setQty(new BigDecimal("3"));
        req.setRate(new BigDecimal("100"));
        req.setSignPrice(new BigDecimal("100"));
        req.setTaxPorcentaje(new BigDecimal("19"));
        req.setTaxFactor(new BigDecimal("19"));
        req.setTaxIco(new BigDecimal("2"));
        req.setPl(new BigDecimal("10"));
        req.setWeightEa(new BigDecimal("1.5"));
        req.setOnhand(new BigDecimal("40"));
        return req;
    }

    @Test
    @DisplayName("a line the quote does not have yet is created with its taxes and product rows")
    void saveItemCreatesNewLine() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(107L, "1001")).thenReturn(Optional.empty());
        repoReturnsSelf();

        QuotationItemResponse response = service().saveItem(107L, itemRequest("1001"));

        QuotationItem saved = captureSavedItem();
        assertThat(saved.getQuotation()).isSameAs(q);
        assertThat(saved.getProductId()).isEqualTo("1001");
        // The three derived figures legacy stores alongside the quantity.
        assertThat(saved.getAmount()).isEqualByComparingTo("300");
        assertThat(saved.getTaxes().getTaxAmount()).isEqualByComparingTo("57");
        assertThat(saved.getProduct().getWeightResult()).isEqualByComparingTo("4.5");
        assertThat(saved.getProduct().getPalletxqty()).isEqualByComparingTo("0.3");
        assertThat(saved.getIcomments()).isEmpty();
        assertThat(saved.getIncludepic()).isZero();
        assertThat(response.getDescription()).isEqualTo("ARROZ 5KG");
    }

    @Test
    @DisplayName("adding a code the quote already carries overwrites that line instead of duplicating it")
    void saveItemUpdatesExistingLine() {
        Quotation q = quotation(1);
        QuotationItem existing = item(q, 500L, "1001");
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(107L, "1001")).thenReturn(Optional.of(existing));
        repoReturnsSelf();

        service().saveItem(107L, itemRequest("1001"));

        QuotationItem saved = captureSavedItem();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(500L);
        assertThat(saved.getQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("the figures a client omits are read as zero, and the pallet size as one")
    void saveItemCoalescesMissingFigures() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(anyLong(), anyString())).thenReturn(Optional.empty());
        repoReturnsSelf();
        QuotationItemRequest req = new QuotationItemRequest();
        req.setProductId("1001");
        req.setQty(null);
        req.setSignPrice(null);
        req.setTaxFactor(null);
        req.setPl(null);
        req.setWeightEa(null);
        req.setTaxIco(null);

        service().saveItem(107L, req);

        QuotationItem saved = captureSavedItem();
        assertThat(saved.getQty()).isEqualByComparingTo("0");
        assertThat(saved.getAmount()).isEqualByComparingTo("0");
        assertThat(saved.getTaxes().getTaxIco()).isEqualByComparingTo("0");
        // pl falls back to one, so the division is defined and yields the qty.
        assertThat(saved.getProduct().getPl()).isEqualByComparingTo("1");
        assertThat(saved.getProduct().getPalletxqty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a pallet size of zero yields no pallet count rather than a division by zero")
    void saveItemGuardsZeroPalletSize() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(anyLong(), anyString())).thenReturn(Optional.empty());
        repoReturnsSelf();
        QuotationItemRequest req = itemRequest("1001");
        req.setPl(BigDecimal.ZERO);

        service().saveItem(107L, req);

        assertThat(captureSavedItem().getProduct().getPalletxqty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a saved line always leaves nothing exempt — the exemption is its own edit")
    void saveItemResetsExemption() {
        Quotation q = quotation(1);
        QuotationItem existing = item(q, 500L, "1001");
        existing.getTaxes().setExcentPorcentaje(new BigDecimal("19"));
        existing.getTaxes().setExcentAmount(new BigDecimal("38"));
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(107L, "1001")).thenReturn(Optional.of(existing));
        repoReturnsSelf();

        service().saveItem(107L, itemRequest("1001"));

        assertThat(captureSavedItem().getTaxes().getExcentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("saving a line recalculates the quote totals, because they are stale by definition")
    void saveItemRecalculatesTotals() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(anyLong(), anyString())).thenReturn(Optional.empty());
        repoReturnsSelf();

        service().saveItem(107L, itemRequest("1001"));

        verify(totalsCalculator).recalculateFor(107L);
    }

    @Test
    @DisplayName("a line cannot be saved onto a quotation that does not exist")
    void saveItemRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().saveItem(404L, itemRequest("1001")))
                .isInstanceOf(QuotationNotFoundException.class);
        verify(quotationItemRepository, never()).save(any(QuotationItem.class));
    }

    // ── addItemsBulk ──────────────────────────────────────────────────────

    private Map<String, Object> line(Object code, Object qty) {
        Map<String, Object> row = new HashMap<>();
        row.put("productId", code);
        row.put("qty", qty);
        return row;
    }

    private Map<String, Object> catalogRow(String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("item_code", code);
        row.put("description", "  ARROZ 5KG  ");
        row.put("sellPrice", "100");
        row.put("sign_price", "100");
        row.put("iva_Percent", "19");
        row.put("iva_Amount", "19");
        row.put("ico_Amount", "0");
        row.put("cu_EA", "1");
        row.put("pl", "10");
        row.put("weight_EA_KG", "1.5");
        row.put("quantityOnHand", "40");
        row.put("soldByWeight", "N");
        row.put("recipe", "N");
        row.put("storageType", "DRY");
        row.put("image1", "  arroz.jpg  ");
        row.put("department", "01");
        row.put("category", "0101");
        return row;
    }

    private void bulkReady() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));
        when(quotationItemRepository.findByQuotation_IdAndProductId(anyLong(), anyString())).thenReturn(Optional.empty());
        repoReturnsSelf();
    }

    @SuppressWarnings("unchecked")
    private List<String> bucket(Map<String, Object> result, String key) {
        return (List<String>) result.get(key);
    }

    @Test
    @DisplayName("pasted codes the catalog knows are added and reported back")
    void bulkAddsKnownCodes() {
        bulkReady();
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));
        when(itemService.getItemByCode("1002", 3)).thenReturn(catalogRow("1002"));

        Map<String, Object> result = service().addItemsBulk(107L, 3,
                List.of(line("1001", "2"), line("1002", "5")));

        assertThat(bucket(result, "added")).containsExactly("1001", "1002");
        assertThat(bucket(result, "notFound")).isEmpty();
        assertThat(bucket(result, "skipped")).isEmpty();
    }

    @Test
    @DisplayName("the catalog row is mapped onto the line, trimming the text columns")
    void bulkMapsCatalogRow() {
        bulkReady();
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));

        service().addItemsBulk(107L, 3, List.of(line("1001", "2")));

        QuotationItem saved = captureSavedItem();
        assertThat(saved.getProduct().getDescription()).isEqualTo("ARROZ 5KG");
        assertThat(saved.getProduct().getPicture1()).isEqualTo("arroz.jpg");
        assertThat(saved.getProduct().getStorageType()).isEqualTo("DRY");
        assertThat(saved.getTaxes().getTaxPorcentaje()).isEqualByComparingTo("19");
        assertThat(saved.getQty()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a catalog row carrying VAT instead of IVA uses whichever of the two is populated")
    void bulkFallsBackToVatColumns() {
        bulkReady();
        Map<String, Object> row = catalogRow("1001");
        row.put("iva_Percent", "0");
        row.put("iva_Amount", "0");
        row.put("vat_Percent", "15");
        row.put("vat_Amount", "15");
        when(itemService.getItemByCode("1001", 3)).thenReturn(row);

        service().addItemsBulk(107L, 3, List.of(line("1001", "1")));

        QuotationItem saved = captureSavedItem();
        assertThat(saved.getTaxes().getTaxPorcentaje()).isEqualByComparingTo("15");
        assertThat(saved.getTaxes().getTaxFactor()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("a catalog row with neither tax column populated leaves the line untaxed")
    void bulkHandlesMissingTaxColumns() {
        bulkReady();
        Map<String, Object> row = catalogRow("1001");
        row.remove("iva_Percent");
        row.remove("iva_Amount");
        when(itemService.getItemByCode("1001", 3)).thenReturn(row);

        service().addItemsBulk(107L, 3, List.of(line("1001", "1")));

        assertThat(captureSavedItem().getTaxes().getTaxPorcentaje()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a price the catalog sends as text that is not a number reads as zero, not as a failure")
    void bulkHandlesUnparseableCatalogFigures() {
        bulkReady();
        Map<String, Object> row = catalogRow("1001");
        row.put("sign_price", "N/A");
        when(itemService.getItemByCode("1001", 3)).thenReturn(row);

        service().addItemsBulk(107L, 3, List.of(line("1001", "1")));

        assertThat(captureSavedItem().getSignPrice()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("text columns the catalog leaves out arrive as empty strings, not as nulls")
    void bulkHandlesMissingTextColumns() {
        bulkReady();
        Map<String, Object> row = catalogRow("1001");
        row.remove("recipe");
        row.remove("storageType");
        row.remove("department");
        when(itemService.getItemByCode("1001", 3)).thenReturn(row);

        service().addItemsBulk(107L, 3, List.of(line("1001", "1")));

        QuotationItem saved = captureSavedItem();
        assertThat(saved.getProduct().getRecipe()).isEmpty();
        assertThat(saved.getProduct().getStorageType()).isEmpty();
        assertThat(saved.getProduct().getDepartment()).isEmpty();
    }

    @Test
    @DisplayName("a repeated code has its quantity summed into the line already there")
    void bulkSumsIntoExistingLine() {
        Quotation q = quotation(1);
        QuotationItem existing = item(q, 500L, "1001");
        existing.setQty(new BigDecimal("4"));
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(107L, "1001")).thenReturn(Optional.of(existing));
        repoReturnsSelf();
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));

        service().addItemsBulk(107L, 3, List.of(line("1001", "3")));

        // Four already on the quote plus three pasted.
        assertThat(captureSavedItem().getQty()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("a line already on the quote with no quantity counts as zero when summing")
    void bulkSumsAgainstNullQuantity() {
        Quotation q = quotation(1);
        QuotationItem existing = item(q, 500L, "1001");
        existing.setQty(null);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdAndProductId(107L, "1001")).thenReturn(Optional.of(existing));
        repoReturnsSelf();
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));

        service().addItemsBulk(107L, 3, List.of(line("1001", "3")));

        assertThat(captureSavedItem().getQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a zero, negative, blank, missing or unparseable quantity all become one")
    void bulkDefaultsQuantityToOne() {
        bulkReady();
        when(itemService.getItemByCode(anyString(), anyInt())).thenAnswer(i -> catalogRow(i.getArgument(0)));
        Map<String, Object> noQtyKey = new HashMap<>();
        noQtyKey.put("productId", "1005");

        List<Map<String, Object>> lines = new ArrayList<>(List.of(
                line("1001", "0"), line("1002", "-4"), line("1003", "   "), line("1004", "dos")));
        lines.add(noQtyKey);

        service().addItemsBulk(107L, 3, lines);

        ArgumentCaptor<QuotationItem> captor = ArgumentCaptor.forClass(QuotationItem.class);
        verify(quotationItemRepository, org.mockito.Mockito.times(5)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(saved ->
                assertThat(saved.getQty()).isEqualByComparingTo("1"));
    }

    @Test
    @DisplayName("a row with no code is passed over silently — it is a blank spreadsheet line")
    void bulkIgnoresBlankCodes() {
        bulkReady();

        Map<String, Object> result = service().addItemsBulk(107L, 3,
                List.of(line(null, "2"), line("   ", "2")));

        assertThat(bucket(result, "added")).isEmpty();
        assertThat(bucket(result, "notFound")).isEmpty();
        assertThat(bucket(result, "skipped")).isEmpty();
        verify(quotationItemRepository, never()).save(any(QuotationItem.class));
    }

    @Test
    @DisplayName("the delivery SKU is refused: it belongs to the Envio panel, not to a pasted line")
    void bulkSkipsPanelOwnedCodes() {
        bulkReady();

        Map<String, Object> result = service().addItemsBulk(107L, 3, List.of(line("888905", "1")));

        assertThat(bucket(result, "skipped")).containsExactly("888905");
        assertThat(bucket(result, "added")).isEmpty();
        verify(itemService, never()).getItemByCode(anyString(), anyInt());
    }

    @Test
    @DisplayName("a code the catalog does not know is reported back, not left to fail the whole paste")
    void bulkReportsUnknownCodes() {
        bulkReady();
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));
        when(itemService.getItemByCode("9999", 3)).thenReturn(null);
        Map<String, Object> empty = new HashMap<>();
        when(itemService.getItemByCode("8888", 3)).thenReturn(empty);

        Map<String, Object> result = service().addItemsBulk(107L, 3,
                List.of(line("9999", "1"), line("8888", "1"), line("1001", "1")));

        assertThat(bucket(result, "notFound")).containsExactly("9999", "8888");
        assertThat(bucket(result, "added")).containsExactly("1001");
    }

    @Test
    @DisplayName("a catalog lookup that blows up costs that one code, not the rest of the paste")
    void bulkSurvivesCatalogFailure() {
        bulkReady();
        when(itemService.getItemByCode("9999", 3)).thenThrow(new RuntimeException("catalog down"));
        when(itemService.getItemByCode("1001", 3)).thenReturn(catalogRow("1001"));

        Map<String, Object> result = service().addItemsBulk(107L, 3,
                List.of(line("9999", "1"), line("1001", "1")));

        assertThat(bucket(result, "notFound")).containsExactly("9999");
        assertThat(bucket(result, "added")).containsExactly("1001");
    }

    @Test
    @DisplayName("a paste cannot be applied to a quotation that does not exist")
    void bulkRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addItemsBulk(404L, 3, List.of(line("1001", "1"))))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    // ── updateItem ────────────────────────────────────────────────────────

    private QuotationItem readyForUpdate(Quotation q) {
        QuotationItem it = item(q, 500L, "1001");
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findById(500L)).thenReturn(Optional.of(it));
        repoReturnsSelf();
        return it;
    }

    @Test
    @DisplayName("the narrow qty endpoint is the same operation as the general one")
    void updateItemQtyDelegates() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItemQty(107L, 500L, Map.of("qty", "5"));

        assertThat(it.getQty()).isEqualByComparingTo("5");
        assertThat(it.getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("changing the quantity moves the amount, the tax, the weight and the pallet count with it")
    void updateItemAppliesQty() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("qty", "5"));

        assertThat(it.getAmount()).isEqualByComparingTo("500");
        assertThat(it.getTaxes().getTaxAmount()).isEqualByComparingTo("95");
        assertThat(it.getProduct().getWeightResult()).isEqualByComparingTo("7.5");
        assertThat(it.getProduct().getPalletxqty()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("a quantity change on a line with no taxes or product row touches only the amount")
    void updateItemAppliesQtyWithoutSubRows() {
        Quotation q = quotation(1);
        QuotationItem bare = QuotationItem.builder().id(501L).quotation(q).productId("1001")
                .signPrice(new BigDecimal("100")).build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findById(501L)).thenReturn(Optional.of(bare));
        repoReturnsSelf();

        service().updateItem(107L, 501L, Map.of("qty", "4"));

        assertThat(bare.getAmount()).isEqualByComparingTo("400");
        assertThat(bare.getTaxes()).isNull();
        assertThat(bare.getProduct()).isNull();
    }

    @Test
    @DisplayName("a quantity change on a line with no sign price yields no amount rather than throwing")
    void updateItemHandlesNullSignPrice() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        it.setSignPrice(null);
        it.getTaxes().setTaxFactor(null);
        it.getProduct().setPl(null);
        it.getProduct().setWeightEa(null);

        service().updateItem(107L, 500L, Map.of("qty", "5"));

        assertThat(it.getAmount()).isEqualByComparingTo("0");
        assertThat(it.getTaxes().getTaxAmount()).isEqualByComparingTo("0");
        // A null pallet size reads as one, so the count equals the quantity.
        assertThat(it.getProduct().getPalletxqty()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a line whose pallet size is zero gets no pallet count")
    void updateItemGuardsZeroPalletSize() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        it.getProduct().setPl(BigDecimal.ZERO);

        service().updateItem(107L, 500L, Map.of("qty", "5"));

        assertThat(it.getProduct().getPalletxqty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an exemption percentage becomes an exempt amount, in proportion to the line's tax")
    void updateItemAppliesExemption() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("exemp", "9.5"));

        assertThat(it.getTaxes().getExcentPorcentaje()).isEqualByComparingTo("9.5");
        // Half of the line's 19% rate, so half of its 38 of tax.
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("19.00");
    }

    @Test
    @DisplayName("an exemption above the line's own tax rate is capped at it")
    void updateItemCapsExemptionAtTaxRate() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("exemp", "50"));

        assertThat(it.getTaxes().getExcentPorcentaje()).isEqualByComparingTo("19");
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("38.00");
    }

    @Test
    @DisplayName("a negative exemption is floored at zero")
    void updateItemFloorsNegativeExemption() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("exemp", "-5"));

        assertThat(it.getTaxes().getExcentPorcentaje()).isEqualByComparingTo("0");
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an untaxed line cannot hold an exemption percentage, because it could never produce an amount")
    void updateItemForcesExemptionToZeroOnUntaxedLine() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        it.getTaxes().setTaxPorcentaje(BigDecimal.ZERO);

        service().updateItem(107L, 500L, Map.of("exemp", "19"));

        assertThat(it.getTaxes().getExcentPorcentaje()).isEqualByComparingTo("0");
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a line with no tax figures at all is treated as untaxed rather than failing")
    void updateItemHandlesNullTaxFigures() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        it.getTaxes().setTaxPorcentaje(null);
        it.getTaxes().setTaxAmount(null);

        service().updateItem(107L, 500L, Map.of("exemp", "19"));

        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the exempt amount is recomputed on a quantity change too, since the tax it derives from moved")
    void updateItemRecomputesExemptionAfterQtyChange() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        // 19% of a 19% rate: the line is fully exempt.
        it.getTaxes().setExcentPorcentaje(new BigDecimal("19"));
        it.getTaxes().setExcentAmount(new BigDecimal("38"));

        service().updateItem(107L, 500L, Map.of("qty", "5"));

        // The tax moved from 38 to 95, so the exempt amount has to follow.
        assertThat(it.getTaxes().getTaxAmount()).isEqualByComparingTo("95");
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("a line with no exemption on record keeps none after an unrelated edit")
    void updateItemKeepsAbsentExemptionAbsent() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        it.getTaxes().setExcentPorcentaje(null);

        service().updateItem(107L, 500L, Map.of("icomments", "sin IVA"));

        assertThat(it.getTaxes().getExcentPorcentaje()).isEqualByComparingTo("0");
        assertThat(it.getTaxes().getExcentAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an edit to a line with no taxes row leaves the exemption alone")
    void updateItemSkipsExemptionWithoutTaxesRow() {
        Quotation q = quotation(1);
        QuotationItem bare = QuotationItem.builder().id(501L).quotation(q).productId("1001").build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findById(501L)).thenReturn(Optional.of(bare));
        repoReturnsSelf();

        service().updateItem(107L, 501L, Map.of("exemp", "19"));

        assertThat(bare.getTaxes()).isNull();
    }

    @Test
    @DisplayName("a per-item comment is stored as sent when it fits the column")
    void updateItemStoresComment() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("icomments", "entregar en bodega 3"));

        assertThat(it.getIcomments()).isEqualTo("entregar en bodega 3");
    }

    @Test
    @DisplayName("a comment longer than the column is truncated rather than rejected by the driver")
    void updateItemTruncatesLongComment() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of("icomments", "x".repeat(600)));

        assertThat(it.getIcomments()).hasSize(500);
    }

    @Test
    @DisplayName("the print-image flag accepts a boolean or the strings a form sends")
    void updateItemReadsIncludePicInEveryShape() {
        Quotation q = quotation(1);
        QuotationItem it = readyForUpdate(q);
        QuotationService service = service();

        service.updateItem(107L, 500L, Map.of("includepic", true));
        assertThat(it.getIncludepic()).isEqualTo(1);

        service.updateItem(107L, 500L, Map.of("includepic", false));
        assertThat(it.getIncludepic()).isZero();

        service.updateItem(107L, 500L, Map.of("includepic", "1"));
        assertThat(it.getIncludepic()).isEqualTo(1);

        service.updateItem(107L, 500L, Map.of("includepic", "0"));
        assertThat(it.getIncludepic()).isZero();

        service.updateItem(107L, 500L, Map.of("includepic", "true"));
        assertThat(it.getIncludepic()).isEqualTo(1);

        service.updateItem(107L, 500L, Map.of("includepic", "FALSE"));
        assertThat(it.getIncludepic()).isZero();
    }

    @Test
    @DisplayName("an edit that changes nothing still recalculates the totals")
    void updateItemRecalculatesTotals() {
        Quotation q = quotation(1);
        readyForUpdate(q);

        service().updateItem(107L, 500L, Map.of());

        verify(totalsCalculator).recalculateFor(107L);
    }

    @Test
    @DisplayName("editing a line of a quotation that does not exist is refused before the line is read")
    void updateItemRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateItem(404L, 500L, Map.of("qty", "1")))
                .isInstanceOf(QuotationNotFoundException.class);
        verify(quotationItemRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("an unknown line id is reported as not found")
    void updateItemRejectsUnknownItem() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));
        when(quotationItemRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateItem(107L, 999L, Map.of("qty", "1")))
                .isInstanceOf(QuotationNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("a line belonging to another quotation cannot be edited through this one")
    void updateItemRejectsForeignItem() {
        Quotation other = Quotation.builder().id(999L).statusId(1).build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));
        when(quotationItemRepository.findById(500L)).thenReturn(Optional.of(item(other, 500L, "1001")));

        assertThatThrownBy(() -> service().updateItem(107L, 500L, Map.of("qty", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to quotation 107");
    }

    // ── deleteItem ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting a line removes it and leaves the totals recalculated")
    void deleteItemRemovesLine() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findById(500L)).thenReturn(Optional.of(item(q, 500L, "1001")));

        service().deleteItem(107L, 500L);

        verify(quotationItemRepository).deleteById(500L);
        verify(totalsCalculator).recalculateFor(107L);
    }

    @Test
    @DisplayName("deleting a line of an unknown quotation is refused")
    void deleteItemRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteItem(404L, 500L))
                .isInstanceOf(QuotationNotFoundException.class);
        verify(quotationItemRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleting an unknown line is reported as not found")
    void deleteItemRejectsUnknownItem() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));
        when(quotationItemRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteItem(107L, 999L))
                .isInstanceOf(QuotationNotFoundException.class);
        verify(quotationItemRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("a line belonging to another quotation cannot be deleted through this one")
    void deleteItemRejectsForeignItem() {
        Quotation other = Quotation.builder().id(999L).statusId(1).build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));
        when(quotationItemRepository.findById(500L)).thenReturn(Optional.of(item(other, 500L, "1001")));

        assertThatThrownBy(() -> service().deleteItem(107L, 500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to quotation 107");
        verify(quotationItemRepository, never()).deleteById(anyLong());
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the cancel reasons come straight from the repository")
    void getCancelReasonsDelegates() {
        List<Map<String, Object>> reasons = List.of(Map.of("id", 1, "reason", "Cliente no acepta"));
        when(quotationCancelRepository.findReasons()).thenReturn(reasons);

        assertThat(service().getCancelReasons()).isSameAs(reasons);
    }

    @Test
    @DisplayName("cancelling a pending quote records the reason")
    void cancelRecordsReason() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));

        service().cancelQuotation(107L, 4);

        verify(quotationCancelRepository).cancel(107L, 4);
    }

    @Test
    @DisplayName("a quote that is no longer pending cannot be cancelled")
    void cancelRejectsNonPending() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(3)));

        assertThatThrownBy(() -> service().cancelQuotation(107L, 4))
                .isInstanceOf(QuotationAlreadySubmittedException.class);
        verify(quotationCancelRepository, never()).cancel(anyLong(), anyInt());
    }

    @Test
    @DisplayName("cancelling without a reason is refused — the reason is what the report is for")
    void cancelRequiresReason() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));

        assertThatThrownBy(() -> service().cancelQuotation(107L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonId is required");
        verify(quotationCancelRepository, never()).cancel(anyLong(), anyInt());
    }

    @Test
    @DisplayName("cancelling an unknown quotation is refused")
    void cancelRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelQuotation(404L, 4))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    // ── getItems ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the lines come back mapped, with their taxes and product columns")
    void getItemsMapsLines() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdOrderByProductIdAsc(107L))
                .thenReturn(List.of(item(q, 500L, "1001")));

        List<QuotationItemResponse> items = service().getItems(107L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuotationId()).isEqualTo(107L);
        assertThat(items.get(0).getTaxAmount()).isEqualByComparingTo("38");
        assertThat(items.get(0).getOnhand()).isEqualByComparingTo("40");
        assertThat(items.get(0).getDescription()).isEqualTo("ARROZ 5KG");
    }

    @Test
    @DisplayName("a line with no taxes or product row still maps, with those columns left null")
    void getItemsMapsBareLine() {
        Quotation q = quotation(1);
        QuotationItem bare = QuotationItem.builder().id(501L).quotation(q).productId("1001")
                .qty(BigDecimal.ONE).build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdOrderByProductIdAsc(107L)).thenReturn(List.of(bare));

        QuotationItemResponse response = service().getItems(107L).get(0);

        assertThat(response.getId()).isEqualTo(501L);
        assertThat(response.getTaxAmount()).isNull();
        assertThat(response.getDescription()).isNull();
    }

    @Test
    @DisplayName("the lines of an unknown quotation are refused rather than returned empty")
    void getItemsRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getItems(404L))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    // ── closeQuotation ────────────────────────────────────────────────────

    @Test
    @DisplayName("closing moves the quote to sale and records the tender, receipt and quote type")
    void closeRecordsSale() {
        Quotation q = quotation(1);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        repoReturnsSelf();
        CloseQuotationRequest req = new CloseQuotationRequest();
        req.setSubmittedBy(1);
        req.setQuoteTypeId(2);
        req.setPaymentNumber("201888600");
        req.setPaymentMethodId("110");

        QuotationResponse response = service().closeQuotation(107L, req);

        assertThat(captureSaved().getStatusId()).isEqualTo(3);
        assertThat(response.getQuoteTypeId()).isEqualTo(2);
        assertThat(response.getPaymentNumber()).isEqualTo("201888600");
        assertThat(response.getPaymentMethodId()).isEqualTo("110");
    }

    @Test
    @DisplayName("a quote that is not pending cannot be closed twice")
    void closeRejectsNonPending() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(3)));

        assertThatThrownBy(() -> service().closeQuotation(107L, new CloseQuotationRequest()))
                .isInstanceOf(QuotationAlreadySubmittedException.class);
        verify(quotationRepository, never()).save(any(Quotation.class));
    }

    @Test
    @DisplayName("closing an unknown quotation is refused")
    void closeRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().closeQuotation(404L, new CloseQuotationRequest()))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    // ── sendToOms ─────────────────────────────────────────────────────────

    private SendToOmsRequest omsRequest() {
        SendToOmsRequest req = new SendToOmsRequest();
        req.setSubmittedBy(1);
        req.setVentanas("2026-09-15T08:00|2026-09-15T12:00|2026-09-15T10:00|4471");
        return req;
    }

    private Map<String, Object> omsContext(Object club) {
        Map<String, Object> context = new HashMap<>();
        context.put("club", club);
        return context;
    }

    private Map<String, Object> clubIn(String iso2) {
        Map<String, Object> club = new HashMap<>();
        club.put("pais_iso2", iso2);
        return club;
    }

    /** A closed quotation with its lines, context and payload all stubbed. */
    private Quotation omsReady(Object club) {
        Quotation q = quotation(3);
        List<QuotationItem> items = List.of(item(q, 500L, "1001"));
        Map<String, Object> payload = Map.of("orderNumber", "107");
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdOrderByProductIdAsc(107L)).thenReturn(items);
        when(omsService.getQuotationContext(eq(107L), eq(3), anyString(), eq(1))).thenReturn(omsContext(club));
        when(omsPayloadBuilder.build(any(), any(), any(), anyString())).thenReturn(payload);
        repoReturnsSelf();
        return q;
    }

    @Test
    @DisplayName("an accepted order has its number stored on the quotation")
    void sendToOmsStoresOrderNumber() {
        Quotation q = omsReady(clubIn("CO"));
        when(omsService.sendPayload(any(), eq("CO"), eq("tok-123"))).thenReturn("{\"orderId\":\"SO-9001\"}");

        String raw = service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        assertThat(raw).contains("SO-9001");
        assertThat(q.getPayment().getQuoteNo()).isEqualTo("SO-9001");
        verify(quotationRepository).save(q);
    }

    @Test
    @DisplayName("a refusal comes back as an HTTP 200, so no order number is stored")
    void sendToOmsLeavesOrderNumberUnsetOnRejection() {
        Quotation q = omsReady(clubIn("CO"));
        String refusal = "{\"errors\":[{\"message\":\"membership blocked\"}]}";
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn(refusal);

        String raw = service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        // The caller gets the refusal verbatim: that body is the only account of why.
        assertThat(raw).isEqualTo(refusal);
        assertThat(q.getPayment()).isNull();
        verify(quotationRepository, never()).save(any(Quotation.class));
    }

    @Test
    @DisplayName("an answer that is not JSON at all is still returned to the caller")
    void sendToOmsReturnsUnparseableAnswer() {
        omsReady(clubIn("CO"));
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("<html>502 Bad Gateway</html>");

        String raw = service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        assertThat(raw).isEqualTo("<html>502 Bad Gateway</html>");
        verify(quotationRepository, never()).save(any(Quotation.class));
    }

    @Test
    @DisplayName("the window and the club's country reach OMS as the payload and the destination")
    void sendToOmsPassesWindowAndCountry() {
        Quotation q = omsReady(clubIn("SV"));
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsPayloadBuilder).build(eq(q), any(), any(),
                eq("2026-09-15T08:00|2026-09-15T12:00|2026-09-15T10:00|4471"));
        verify(omsService).sendPayload(any(), eq("SV"), eq("tok-123"));
    }

    @Test
    @DisplayName("a context with no club falls back to Costa Rica")
    void sendToOmsDefaultsCountryWithoutClub() {
        omsReady(null);
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsService).sendPayload(any(), eq("CR"), anyString());
    }

    @Test
    @DisplayName("a club that is not a map at all falls back to Costa Rica")
    void sendToOmsDefaultsCountryWhenClubIsNotAMap() {
        omsReady("no soy un mapa");
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsService).sendPayload(any(), eq("CR"), anyString());
    }

    @Test
    @DisplayName("a club whose country code is blank falls back to Costa Rica")
    void sendToOmsDefaultsCountryWhenBlank() {
        omsReady(clubIn("   "));
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsService).sendPayload(any(), eq("CR"), anyString());
    }

    @Test
    @DisplayName("a club whose country code is missing falls back to Costa Rica")
    void sendToOmsDefaultsCountryWhenAbsent() {
        omsReady(new HashMap<String, Object>());
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsService).sendPayload(any(), eq("CR"), anyString());
    }

    @Test
    @DisplayName("a quotation with no customer is sent with no membership rather than failing")
    void sendToOmsHandlesMissingCustomer() {
        Quotation q = quotation(3);
        q.setCustomer(null);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        when(quotationItemRepository.findByQuotation_IdOrderByProductIdAsc(107L)).thenReturn(List.of());
        when(omsService.getQuotationContext(eq(107L), eq(3), isNull(), eq(1))).thenReturn(omsContext(clubIn("CO")));
        when(omsPayloadBuilder.build(any(), any(), any(), anyString())).thenReturn(Map.of());
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");
        repoReturnsSelf();

        service(startIdp("{\"access_token\":\"tok-123\"}")).sendToOms(107L, omsRequest());

        verify(omsService).getQuotationContext(107L, 3, null, 1);
    }

    @Test
    @DisplayName("a quotation that is not closed cannot be sent — OMS would take an order nobody paid")
    void sendToOmsRequiresClosedQuotation() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(1)));

        assertThatThrownBy(() -> service().sendToOms(107L, omsRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be closed (status=3). Current: 1");
        verify(omsService, never()).sendPayload(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("sending an unknown quotation is refused")
    void sendToOmsRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().sendToOms(404L, omsRequest()))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    @Test
    @DisplayName("an identity provider that cannot be reached stops the send before OMS is called")
    void sendToOmsFailsWhenTokenCannotBeObtained() {
        omsReady(clubIn("CO"));

        assertThatThrownBy(() -> service(deadIdp()).sendToOms(107L, omsRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error obteniendo token OMS");
        verify(omsService, never()).sendPayload(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a token response with no access_token yields an empty token rather than a null one")
    void sendToOmsToleratesTokenlessResponse() {
        omsReady(clubIn("CO"));
        when(omsService.sendPayload(any(), anyString(), anyString())).thenReturn("{\"orderId\":1}");

        service(startIdp("{\"error\":\"invalid_client\"}")).sendToOms(107L, omsRequest());

        verify(omsService).sendPayload(any(), anyString(), eq(""));
    }

    // ── getOmsStatus ──────────────────────────────────────────────────────

    private Quotation withOrderNumber(String quoteNo) {
        Quotation q = quotation(3);
        q.setPayment(QuotationPayment.builder().quotation(q).paidStatus(1).serviceId(0)
                .paymentMethodId("110").quoteNo(quoteNo).build());
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));
        return q;
    }

    @Test
    @DisplayName("the OMS history comes back parsed, not as a string of JSON")
    void getOmsStatusReturnsParsedHistory() {
        withOrderNumber("SO-9001");
        when(omsService.getOrderStatusHistory(eq("SO-9001"), eq("tok-123")))
                .thenReturn("{\"status\":\"DELIVERED\"}");

        Object status = service(startIdp("{\"access_token\":\"tok-123\"}")).getOmsStatus(107L);

        assertThat(status).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) status).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("a history answer that is not JSON is handed back as it arrived")
    void getOmsStatusReturnsRawWhenUnparseable() {
        withOrderNumber("SO-9001");
        when(omsService.getOrderStatusHistory(anyString(), anyString()))
                .thenReturn("<html><body>504 Gateway Timeout</body></html>");

        Object status = service(startIdp("{\"access_token\":\"tok-123\"}")).getOmsStatus(107L);

        assertThat(status).isEqualTo("<html><body>504 Gateway Timeout</body></html>");
    }

    @Test
    @DisplayName("a gateway error that begins with its status code is read as that number, not as text")
    void getOmsStatusParsesNumericErrorBody() {
        withOrderNumber("SO-9001");
        // Worth pinning rather than fixing here: Jackson reads the leading 504
        // as a whole JSON value and stops, so a proxy answering
        // "504 Gateway Timeout" reaches the caller as the integer 504 and never
        // takes the fallback below. Callers that show this to an operator get a
        // bare number, which is why the modal renders the raw response instead.
        when(omsService.getOrderStatusHistory(anyString(), anyString())).thenReturn("504 Gateway Timeout");

        assertThat(service(startIdp("{\"access_token\":\"tok-123\"}")).getOmsStatus(107L))
                .isEqualTo(504);
    }

    @Test
    @DisplayName("a quotation that was never sent to OMS has no status to ask about")
    void getOmsStatusRequiresPaymentRow() {
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation(3)));

        assertThatThrownBy(() -> service().getOmsStatus(107L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no OMS order number yet");
    }

    @Test
    @DisplayName("a closed quotation whose order was refused has no status to ask about either")
    void getOmsStatusRequiresOrderNumber() {
        withOrderNumber(null);

        assertThatThrownBy(() -> service().getOmsStatus(107L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no OMS order number yet");
        verify(omsService, never()).getOrderStatusHistory(anyString(), anyString());
    }

    @Test
    @DisplayName("asking for the status of an unknown quotation is refused")
    void getOmsStatusRejectsUnknownQuotation() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getOmsStatus(404L))
                .isInstanceOf(QuotationNotFoundException.class);
    }

    @Test
    @DisplayName("an unreachable identity provider stops the status request too")
    void getOmsStatusFailsWhenTokenCannotBeObtained() {
        withOrderNumber("SO-9001");

        assertThatThrownBy(() -> service(deadIdp()).getOmsStatus(107L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error obteniendo token OMS");
        verify(omsService, never()).getOrderStatusHistory(anyString(), anyString());
    }
}
