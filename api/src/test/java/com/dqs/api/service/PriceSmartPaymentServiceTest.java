package com.dqs.api.service;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationPaymentAttempt;
import com.dqs.api.repository.QuotationPaymentAttemptRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.support.NativeQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PriceSmart Payments gateway.
 *
 * A payment link is money: the invoice number the customer pays against is
 * built here, and it has to be unique per attempt or a retry collects twice
 * against the same invoice. Most of these tests are about that number and about
 * the attempt row that ties it back to a quotation.
 *
 * The HTTP is real, against a JDK HttpServer on a loopback port, because the
 * service opens its own connections. Nothing leaves the machine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceSmartPaymentServiceTest {

    @Mock private NativeQueries nativeQueries;
    @Mock private QuotationPaymentAttemptRepository attemptRepository;
    @Mock private QuotationRepository quotationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    /** Requests the stand-in service received: "METHOD path" plus the body. */
    private final List<String> received = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        received.clear();
        bodies.clear();
        authorizations.clear();
    }

    private PriceSmartPaymentService service(String baseUrl) {
        return service(baseUrl, baseUrl);
    }

    /** The token endpoint and the API are separate settings, so a test can fail one. */
    private PriceSmartPaymentService service(String apiUrl, String tokenHost) {
        PriceSmartPaymentService s = new PriceSmartPaymentService(
                nativeQueries, attemptRepository, quotationRepository, objectMapper);
        set(s, "paymentsBaseUrl", "https://pay.pricesmart.test");
        set(s, "paymentsApiUrl", apiUrl);
        set(s, "paymentsIdpTokenUrl", tokenHost + "/token");
        set(s, "callbackUrl", "https://quotecenter.test/api/v1/payments/callback");
        set(s, "returnUrl", "https://quotecenter.test/done");
        set(s, "clientId", "quotecenter");
        set(s, "clientSecret", "s3cr3t");
        return s;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = PriceSmartPaymentService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /**
     * A stand-in payments service: /token answers `tokenBody`, everything else
     * answers `apiStatus` with `apiBody`.
     */
    private String startServer(String tokenBody, int apiStatus, String apiBody) {
        return startServer(exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean isToken = exchange.getRequestURI().getPath().endsWith("/token");
            byte[] out = (isToken ? tokenBody : apiBody).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(isToken ? 200 : apiStatus, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
    }

    private String startServer(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                try {
                    handler.handle(exchange);
                } catch (IOException e) {
                    exchange.close();
                }
            });
            server.start();
            return "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static String deadUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Map<String, Object> quoteRow(String name, String membership) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 107L);
        row.put("customer_membership", membership);
        row.put("customer_name", name);
        row.put("store_id", 6101);
        row.put("pais_iso2", "co");
        row.put("store_nombre", "Barranquilla");
        return row;
    }

    private Map<String, Object> itemRow(String code, String description, double qty, double rate) {
        Map<String, Object> row = new HashMap<>();
        row.put("product_id", code);
        row.put("description", description);
        row.put("qty", qty);
        row.put("rate", rate);
        row.put("category", "0101");
        return row;
    }

    private void stubQuote(Map<String, Object> quote, List<Map<String, Object>> items) {
        when(nativeQueries.list(contains("FROM quotations"), any()))
                .thenReturn(quote == null ? List.of() : List.of(quote));
        when(nativeQueries.list(contains("FROM quotation_items"), any())).thenReturn(items);
        when(nativeQueries.list(contains("FROM ps_socios"), any())).thenReturn(List.of());
        when(attemptRepository.countByQuotation_Id(anyLong())).thenReturn(0L);
        when(attemptRepository.findByInvoiceId(any())).thenReturn(Optional.empty());
        when(quotationRepository.getReferenceById(anyLong()))
                .thenReturn(Quotation.builder().id(107L).storeId(6101).build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentBody() {
        try {
            // The token POST is first; the payment request is the one with JSON.
            String json = bodies.stream().filter(b -> b.startsWith("{")).findFirst().orElseThrow();
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstDetail() {
        return ((List<Map<String, Object>>) sentBody().get("paymentRequestDetail")).get(0);
    }

    private QuotationPaymentAttempt savedAttempt() {
        ArgumentCaptor<QuotationPaymentAttempt> captor =
                ArgumentCaptor.forClass(QuotationPaymentAttempt.class);
        verify(attemptRepository).save(captor.capture());
        return captor.getValue();
    }

    private static final String OK_RESPONSE = "{\"authorizationToken\":\"auth-abc\"}";

    // ── The invoice number ────────────────────────────────────────────────

    @Test
    @DisplayName("the invoice number is the quotation and the attempt, zero-padded to ten digits")
    void invoiceNumberIsQuotationPlusAttempt() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 2, 119)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        Map<String, Object> result = service(url).createPaymentRequest(107L, "1.2.3.4");

        // Eight digits of quotation and two of attempt: the format the payment
        // API validates against.
        assertThat(result).containsEntry("invoice", "0000010701");
        assertThat(firstDetail()).containsEntry("invoiceId", "0000010701");
    }

    @Test
    @DisplayName("a second attempt on the same quotation gets its own invoice number")
    void aRetryGetsItsOwnInvoiceNumber() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        when(attemptRepository.countByQuotation_Id(107L)).thenReturn(4L);
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        // Reusing the number would let a retry collect twice against one invoice.
        assertThat(service(url).createPaymentRequest(107L, "1.2.3.4"))
                .containsEntry("invoice", "0000010705");
    }

    @Test
    @DisplayName("an attempt already on record has its token refreshed rather than a second row added")
    void aKnownInvoiceRefreshesItsToken() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        QuotationPaymentAttempt known = QuotationPaymentAttempt.builder()
                .id(3L).invoiceId("0000010701").authorizationToken("auth-old").build();
        when(attemptRepository.findByInvoiceId("0000010701")).thenReturn(Optional.of(known));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        // invoice_id is unique, so this is what the old ON DUPLICATE KEY did.
        assertThat(savedAttempt()).isSameAs(known);
        assertThat(known.getAuthorizationToken()).isEqualTo("auth-abc");
    }

    @Test
    @DisplayName("the attempt is recorded so an invoice can be traced back to its quotation")
    void theAttemptTiesTheInvoiceToTheQuotation() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        QuotationPaymentAttempt row = savedAttempt();
        assertThat(row.getInvoiceId()).isEqualTo("0000010701");
        assertThat(row.getAuthorizationToken()).isEqualTo("auth-abc");
        assertThat(row.getQuotation().getId()).isEqualTo(107L);
    }

    @Test
    @DisplayName("a response with no authorization token still records the attempt, with none")
    void anAttemptWithoutATokenIsStillRecorded() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, "{\"error\":\"declined\"}");

        Map<String, Object> result = service(url).createPaymentRequest(107L, "1.2.3.4");

        // No iframe to open, but the invoice still travels back so the frontend
        // can ask about its status.
        assertThat(result).doesNotContainKey("iframeUrl").containsEntry("invoice", "0000010701");
        assertThat(savedAttempt().getAuthorizationToken()).isNull();
    }

    // ── The request body ──────────────────────────────────────────────────

    @Test
    @DisplayName("the widget URL is built from the token the service returned")
    void iframeUrlIsBuiltFromTheToken() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        assertThat(service(url).createPaymentRequest(107L, "1.2.3.4"))
                .containsEntry("iframeUrl", "https://pay.pricesmart.test/?authorization_token=auth-abc");
    }

    @Test
    @DisplayName("the amount is the sum of quantity times rate over every line")
    void amountIsTheSumOfTheLines() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"),
                List.of(itemRow("1001", "ARROZ", 2, 119), itemRow("1002", "FRIJOL", 3, 50)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("localTotalAmount", 388.0);
        assertThat(firstDetail()).containsEntry("amount", 388.0);
    }

    @Test
    @DisplayName("a quotation that totals nothing is sent as one, because zero is refused")
    void zeroTotalBecomesOne() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 0, 119)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("localTotalAmount", 1.0);
    }

    @Test
    @DisplayName("the cart sends the item description in the category field, as the API spec asks")
    void cartSendsDescriptionAsCategory() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ 5KG", 2, 119)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cart = (List<Map<String, Object>>) firstDetail().get("shoppingCart");
        assertThat(cart).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("itemId", "1001");
            // Not the department code: the spec wants the human description here.
            assertThat(line).containsEntry("category", "ARROZ 5KG");
            assertThat(line).containsEntry("quantity", 2.0).containsEntry("price", 119.0);
        });
    }

    @Test
    @DisplayName("a line with no description falls back to General rather than sending nothing")
    void cartFallsBackForAMissingDescription() {
        Map<String, Object> item = itemRow("1001", null, 1, 100);
        item.remove("description");
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(item));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cart = (List<Map<String, Object>>) firstDetail().get("shoppingCart");
        assertThat(cart.get(0)).containsEntry("category", "General");
    }

    @Test
    @DisplayName("the customer name is split into first and last for the shipping block")
    void nameIsSplitForShipping() {
        stubQuote(quoteRow("JUAN CARLOS PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        Map<String, Object> shipping = (Map<String, Object>) sentBody().get("shipping");
        // Split once, so everything after the first word is the surname.
        assertThat(shipping).containsEntry("firstName", "JUAN")
                .containsEntry("lastName", "CARLOS PEREZ")
                .containsEntry("city", "Barranquilla")
                .containsEntry("country", "CO")
                .containsEntry("deliverySpeed", "PICK_UP");
    }

    @Test
    @DisplayName("a single-word name is used for both halves rather than leaving one empty")
    void aSingleWordNameFillsBoth() {
        stubQuote(quoteRow("ACME", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        Map<String, Object> shipping = (Map<String, Object>) sentBody().get("shipping");
        assertThat(shipping).containsEntry("firstName", "ACME").containsEntry("lastName", "ACME");
    }

    @Test
    @DisplayName("a quotation with no customer name still ships, under placeholders")
    void aMissingNameFallsBack() {
        stubQuote(quoteRow(null, "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        Map<String, Object> shipping = (Map<String, Object>) sentBody().get("shipping");
        assertThat(shipping).containsEntry("firstName", "N/A").containsEntry("lastName", "N/A");
    }

    @Test
    @DisplayName("the country is upper-cased, because the payment API matches on it exactly")
    void countryIsUpperCased() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        // The club table stores it lower-case in places.
        assertThat(sentBody()).containsEntry("country", "CO").containsEntry("club", "6101");
    }

    @Test
    @DisplayName("the club name falls back when the row does not carry one")
    void clubNameFallsBack() {
        Map<String, Object> quote = quoteRow("JUAN PEREZ", "70012345678901");
        quote.remove("store_nombre");
        stubQuote(quote, List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        @SuppressWarnings("unchecked")
        Map<String, Object> shipping = (Map<String, Object>) sentBody().get("shipping");
        assertThat(shipping).containsEntry("city", "Cartago");
    }

    @Test
    @DisplayName("the caller's IP travels, and a missing one becomes an explicit zero address")
    void ipAddressTravels() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);
        PriceSmartPaymentService service = service(url);

        service.createPaymentRequest(107L, "1.2.3.4");
        assertThat(sentBody()).containsEntry("ipAddress", "1.2.3.4");

        bodies.clear();
        service.createPaymentRequest(107L, null);
        assertThat(sentBody()).containsEntry("ipAddress", "0.0.0.0");
    }

    @Test
    @DisplayName("the callback and return URLs are the configured ones, not built here")
    void callbackAndReturnUrlsAreConfigured() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody())
                .containsEntry("callBackUrl", "https://quotecenter.test/api/v1/payments/callback")
                .containsEntry("returnUrl", "https://quotecenter.test/done")
                .containsEntry("paymentRequestType", "payment_link")
                .containsEntry("process", "ORDER_PLACEMENT")
                .containsEntry("channel", "ECOM");
    }

    // ── The customer's email ──────────────────────────────────────────────

    @Test
    @DisplayName("the email comes from the club's own member record when there is one")
    void emailComesFromTheMemberRecord() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        when(nativeQueries.list(contains("FROM ps_socios"), any()))
                .thenReturn(List.of(Map.of("email", "compras@acme.co")));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("email", "compras@acme.co");
    }

    @Test
    @DisplayName("a member with no email on file gets a per-quotation address, so the link still sends")
    void missingEmailFallsBackPerQuotation() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        when(nativeQueries.list(contains("FROM ps_socios"), any()))
                .thenReturn(List.of(new HashMap<String, Object>()));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("email", "quote-107@pricesmart.com");
    }

    @Test
    @DisplayName("a quotation with no membership does not even ask for an email")
    void noMembershipSkipsTheLookup() {
        stubQuote(quoteRow("JUAN PEREZ", null), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("email", "quote-107@pricesmart.com")
                .containsEntry("clientKey", "");
        verify(nativeQueries, org.mockito.Mockito.never()).list(contains("FROM ps_socios"), any());
    }

    @Test
    @DisplayName("a blank membership is treated as none")
    void blankMembershipIsTreatedAsNone() {
        stubQuote(quoteRow("JUAN PEREZ", "   "), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("email", "quote-107@pricesmart.com");
    }

    @Test
    @DisplayName("a failure reading the member record falls back instead of failing the payment")
    void emailLookupFailureFallsBack() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        when(nativeQueries.list(contains("FROM ps_socios"), any()))
                .thenThrow(new RuntimeException("table missing"));
        String url = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(sentBody()).containsEntry("email", "quote-107@pricesmart.com");
    }

    // ── Failures ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a quotation that does not exist is refused before any request goes out")
    void unknownQuotationIsRefused() {
        stubQuote(null, List.of());

        assertThatThrownBy(() -> service(deadUrl()).createPaymentRequest(404L, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quotation not found: 404");
    }

    @Test
    @DisplayName("an identity provider that answers no token stops the request")
    void noTokenStopsTheRequest() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"error\":\"invalid_client\"}", 200, OK_RESPONSE);

        assertThatThrownBy(() -> service(url).createPaymentRequest(107L, "1.2.3.4"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not obtain payments IDP token");
        verify(attemptRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("an unreachable identity provider stops it too")
    void unreachableIdpStopsTheRequest() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));

        assertThatThrownBy(() -> service(deadUrl()).createPaymentRequest(107L, "1.2.3.4"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not obtain payments IDP token");
    }

    @Test
    @DisplayName("an answer that is not JSON is handed back under a raw key rather than lost")
    void unparseableAnswerIsHandedBackRaw() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer("{\"access_token\":\"tok\"}", 502, "<html>Bad Gateway</html>");

        Map<String, Object> result = service(url).createPaymentRequest(107L, "1.2.3.4");

        assertThat(result).containsEntry("raw", "<html>Bad Gateway</html>")
                .containsEntry("invoice", "0000010701");
    }

    // ── Status ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a status check carries a fresh token as a bearer header")
    void statusCarriesABearerToken() {
        String url = startServer("{\"access_token\":\"tok-xyz\"}", 200, "{\"status_code\":\"AUTHORIZED\"}");

        Map<String, Object> status = service(url).getPaymentStatus("0000010701");

        assertThat(status).containsEntry("status_code", "AUTHORIZED");
        assertThat(received).contains("POST /token", "GET /api/wallet/get-status");
        assertThat(authorizations).contains("Bearer tok-xyz");
    }

    @Test
    @DisplayName("an error answer is parsed and returned, not raised")
    void statusErrorIsReturned() {
        String url = startServer("{\"access_token\":\"tok\"}", 404, "{\"error\":\"unknown invoice\"}");

        assertThat(service(url).getPaymentStatus("0000010701"))
                .containsEntry("error", "unknown invoice");
    }

    @Test
    @DisplayName("a status answer that is not JSON comes back under a raw key")
    void unparseableStatusIsHandedBackRaw() {
        String url = startServer("{\"access_token\":\"tok\"}", 200, "AUTHORIZED");

        assertThat(service(url).getPaymentStatus("0000010701")).containsEntry("raw", "AUTHORIZED");
    }

    @Test
    @DisplayName("a status request that fails after the token was obtained raises too")
    void statusFailureAfterTheTokenRaises() {
        String tokenHost = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);

        // Token from the live server, status from a port with nothing on it.
        assertThatThrownBy(() -> service(deadUrl(), tokenHost).getPaymentStatus("0000010701"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error checking payment status");
    }

    @Test
    @DisplayName("an identity provider answering an error is read from the error stream, then refused")
    void tokenErrorAnswerIsReadAndRefused() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String url = startServer(exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8);
            // 401, so the body arrives on the error stream rather than the input one.
            exchange.sendResponseHeaders(401, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });

        assertThatThrownBy(() -> service(url).createPaymentRequest(107L, "1.2.3.4"))
                .isInstanceOf(RuntimeException.class)
                // The refusal reaches the message, so the cause is in the log.
                .hasMessageContaining("invalid_client");
    }

    @Test
    @DisplayName("a payment request that cannot be posted names the URL it failed on")
    void postFailureNamesTheUrl() {
        stubQuote(quoteRow("JUAN PEREZ", "70012345678901"), List.of(itemRow("1001", "ARROZ", 1, 100)));
        String tokenHost = startServer("{\"access_token\":\"tok\"}", 200, OK_RESPONSE);
        String dead = deadUrl();

        assertThatThrownBy(() -> service(dead, tokenHost).createPaymentRequest(107L, "1.2.3.4"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("POST " + dead + "/api/paymentRequest/create failed");
    }

    @Test
    @DisplayName("an unreachable payments service raises rather than reporting an unpaid quote as paid")
    void unreachableStatusRaises() {
        // Reporting "not authorized" on a network failure would look identical
        // to a genuine refusal.
        assertThatThrownBy(() -> service(deadUrl()).getPaymentStatus("0000010701"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not obtain payments IDP token");
    }
}
