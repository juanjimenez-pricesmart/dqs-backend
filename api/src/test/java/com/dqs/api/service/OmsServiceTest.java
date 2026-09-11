package com.dqs.api.service;

import com.dqs.api.repository.support.NativeQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OMS gateway: the context it assembles from the database, and the two
 * calls it makes over the wire.
 *
 * The HTTP is real — a JDK HttpServer on a loopback port stands in for the
 * membership API and for OMS itself — because the service builds its own
 * HttpURLConnection and there is no client to stub. What that buys is the
 * error paths: a 500, a body that is not JSON, and a port with nothing
 * listening all behave differently, and none of them may throw.
 *
 * The service also replaces the JVM's default SSL socket factory and hostname
 * verifier, process-wide, on every membership lookup. These tests put both back
 * afterwards so the rest of the suite is not left with TLS verification off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OmsServiceTest {

    @Mock private NativeQueries nativeQueries;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private SSLSocketFactory savedFactory;
    private HostnameVerifier savedVerifier;

    /** The requests the stand-in server received, in order. */
    private final List<String> received = new ArrayList<>();

    @BeforeEach
    void rememberTlsDefaults() {
        savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        savedVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    }

    @AfterEach
    void restore() {
        // getMemberData disables certificate and hostname checking for the whole
        // JVM. Leaving that on would quietly weaken every other test.
        HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(savedVerifier);
        if (server != null) {
            server.stop(0);
            server = null;
        }
        received.clear();
    }

    private OmsService service(String baseUrl) {
        OmsService s = new OmsService(nativeQueries, objectMapper);
        set(s, "omsBaseUrl", baseUrl);
        set(s, "businessApiUrl", baseUrl);
        set(s, "timeout", 5000);
        return s;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = OmsService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /** Starts a server answering every path with `status` and `body`. */
    private String startServer(int status, String body) {
        return startServer(exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
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

    /** A port with nothing listening on it. */
    private static String deadUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static final String MEMBER_JSON =
            "{\"firstName\":\"JUAN\",\"lastName\":\"PEREZ\",\"email\":\"api@acme.co\"," +
            "\"cellPhone\":\"3001111111\",\"addressLine1\":\"API ADDRESS\"}";

    private Map<String, Object> clubRow(String iso2) {
        Map<String, Object> row = new HashMap<>();
        row.put("ps_tienda_id", 6101);
        row.put("nombre", "Barranquilla");
        row.put("moneda", "COP");
        row.put("idioma", "es");
        row.put("pais_iso2", iso2);
        row.put("impuesto_operacion", "+");
        return row;
    }

    /** Stubs the club, rate and user queries by their leading SQL. */
    private void stubDatabase(Map<String, Object> club, Object rate, Map<String, Object> user) {
        when(nativeQueries.list(contains("FROM ps_tienda"), any()))
                .thenReturn(club == null ? List.of() : List.of(club));
        when(nativeQueries.list(contains("ps_tasa_cambio"), any()))
                .thenReturn(rate == null ? List.of() : List.of(Map.of("ps_tasa_cambio_tipocambio", rate)));
        when(nativeQueries.list(contains("FROM users"), any()))
                .thenReturn(user == null ? List.of() : List.of(user));
        when(nativeQueries.list(contains("FROM ps_socios "), any())).thenReturn(List.of());
        when(nativeQueries.list(contains("ps_socios_fel"), any(), any())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> context, String key) {
        return (Map<String, Object>) context.get(key);
    }

    // ── Context: the club ─────────────────────────────────────────────────

    @Test
    @DisplayName("the context carries the club, its rate and the user who is selling")
    void contextCarriesClubRateAndUser() {
        stubDatabase(clubRow("CO"), 4150.25, Map.of("id", 1, "email", "vendedor@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> context = service(url).buildOmsContext(107L, 6101, "70012345678901", 1);

        assertThat(mapAt(context, "club")).containsEntry("moneda", "COP");
        assertThat(context).containsEntry("exchange_rate", 4150.25);
        assertThat(mapAt(context, "user")).containsEntry("email", "vendedor@pricesmart.com");
        // Never populated: the delivery reaches the payload from its own table.
        assertThat(context).containsEntry("delivery", null);
    }

    @Test
    @DisplayName("a club that is not in the table leaves an empty club rather than failing")
    void unknownClubLeavesAnEmptyMap() {
        stubDatabase(null, 4150.25, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> context = service(url).buildOmsContext(107L, 9999, "70012345678901", 1);

        assertThat(mapAt(context, "club")).isEmpty();
        // With no club there is no country, so the rate query asks for Costa Rica.
        verify(nativeQueries).list(contains("ps_tasa_cambio"), eq("CR"));
    }

    @Test
    @DisplayName("a country with no exchange rate on record is treated as one to one")
    void missingRateIsOne() {
        stubDatabase(clubRow("CO"), null, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        assertThat(service(url).buildOmsContext(107L, 6101, "70012345678901", 1))
                .containsEntry("exchange_rate", 1.0);
    }

    // ── Context: the user's email ─────────────────────────────────────────

    @Test
    @DisplayName("a user with no email gets a synthetic one, because OMS rejects an order without")
    void userWithoutEmailGetsASyntheticOne() {
        Map<String, Object> user = new HashMap<>();
        user.put("id", 7);
        user.put("email", null);
        stubDatabase(clubRow("CO"), 1, user);
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> context = service(url).buildOmsContext(107L, 6101, "70012345678901", 7);

        assertThat(mapAt(context, "user")).containsEntry("email", "dqs-user-7@pricesmart.com");
    }

    @Test
    @DisplayName("an email that is not an email is replaced too, not passed on")
    void malformedEmailIsReplaced() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 7, "email", "vendedor"));
        String url = startServer(200, MEMBER_JSON);

        // OMS validates OrderCreatedBy, so "vendedor" would fail the whole order.
        assertThat(mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 7), "user"))
                .containsEntry("email", "dqs-user-7@pricesmart.com");
    }

    @Test
    @DisplayName("a blank email is replaced as well")
    void blankEmailIsReplaced() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 7, "email", "   "));
        String url = startServer(200, MEMBER_JSON);

        assertThat(mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 7), "user"))
                .containsEntry("email", "dqs-user-7@pricesmart.com");
    }

    @Test
    @DisplayName("a user who is not in the table at all still yields a usable creator")
    void unknownUserStillYieldsACreator() {
        stubDatabase(clubRow("CO"), 1, null);
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> user = mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 42), "user");

        assertThat(user).containsEntry("id", 42)
                .containsEntry("email", "dqs-user-42@pricesmart.com");
    }

    // ── Context: the member ───────────────────────────────────────────────

    @Test
    @DisplayName("the member comes from the membership API, asked for by number")
    void memberComesFromTheApi() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> member = mapAt(
                service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member");

        assertThat(member).containsEntry("firstName", "JUAN").containsEntry("email", "api@acme.co");
        assertThat(received).containsExactly("GET /api/members/70012345678901");
    }

    @Test
    @DisplayName("what the club has on file overrides what the API returned")
    void localMemberDataWins() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        Map<String, Object> local = new HashMap<>();
        local.put("addressLine1", "CRA 43 N 82 66");
        local.put("cellPhone", "3009999999");
        local.put("email", "compras@acme.co");
        local.put("businessName", "ACME SA");
        when(nativeQueries.list(contains("FROM ps_socios "), any())).thenReturn(List.of(local));
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> member = mapAt(
                service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member");

        // The club's own record is the one the operator maintains, so it wins.
        assertThat(member).containsEntry("addressLine1", "CRA 43 N 82 66")
                .containsEntry("cellPhone", "3009999999")
                .containsEntry("email", "compras@acme.co")
                .containsEntry("businessName", "ACME SA")
                // Untouched: there is no local column for it.
                .containsEntry("firstName", "JUAN");
    }

    @Test
    @DisplayName("a local record with empty columns does not blank out what the API sent")
    void localNullsDoNotOverride() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        Map<String, Object> local = new HashMap<>();
        local.put("addressLine1", null);
        local.put("cellPhone", null);
        local.put("email", null);
        local.put("businessName", null);
        when(nativeQueries.list(contains("FROM ps_socios "), any())).thenReturn(List.of(local));
        String url = startServer(200, MEMBER_JSON);

        assertThat(mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member"))
                .containsEntry("email", "api@acme.co")
                .containsEntry("addressLine1", "API ADDRESS");
    }

    @Test
    @DisplayName("a failure reading the local record leaves the API's answer standing")
    void localLookupFailureIsSurvivable() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        when(nativeQueries.list(contains("FROM ps_socios "), any()))
                .thenThrow(new RuntimeException("table missing"));
        String url = startServer(200, MEMBER_JSON);

        assertThat(mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member"))
                .containsEntry("email", "api@acme.co");
    }

    @Test
    @DisplayName("a membership the API does not know falls back to placeholders OMS accepts")
    void unknownMembershipFallsBack() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(404, "{\"error\":\"not found\"}");

        Map<String, Object> member = mapAt(
                service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member");

        assertThat(member).containsEntry("firstName", "N/A")
                .containsEntry("email", "noreply@pricesmart.com")
                .containsEntry("cellPhone", "0000000000")
                .containsEntry("countryCode", "CR");
        // The fallback is built here, so the local override never runs.
        verify(nativeQueries, never()).list(contains("FROM ps_socios "), any());
    }

    @Test
    @DisplayName("an API answer that is not JSON falls back rather than propagating")
    void unparseableMemberAnswerFallsBack() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, "<html>maintenance</html>");

        assertThat(mapAt(service(url).buildOmsContext(107L, 6101, "70012345678901", 1), "member"))
                .containsEntry("firstName", "N/A");
    }

    @Test
    @DisplayName("a membership API that cannot be reached falls back too")
    void unreachableApiFallsBack() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));

        assertThat(mapAt(service(deadUrl()).buildOmsContext(107L, 6101, "70012345678901", 1), "member"))
                .containsEntry("email", "noreply@pricesmart.com");
    }

    // ── Context: electronic invoicing ─────────────────────────────────────

    @Test
    @DisplayName("Guatemala carries its taxpayer record, keyed by membership and quotation")
    void guatemalaCarriesFel() {
        stubDatabase(clubRow("GT"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        when(nativeQueries.list(contains("ps_socios_fel"), any(), any()))
                .thenReturn(List.of(Map.of("nit", "900123456", "businessname", "ACME SA")));
        String url = startServer(200, MEMBER_JSON);

        Map<String, Object> context = service(url).buildOmsContext(107L, 6301, "70012345678901", 1);

        assertThat(mapAt(context, "fel")).containsEntry("nit", "900123456");
        verify(nativeQueries).list(contains("ps_socios_fel"), eq("70012345678901"), eq(107L));
    }

    @Test
    @DisplayName("a Guatemalan quote with no taxpayer record on file carries none")
    void guatemalaWithoutFel() {
        stubDatabase(clubRow("GT"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        assertThat(service(url).buildOmsContext(107L, 6301, "70012345678901", 1))
                .containsEntry("fel", null);
    }

    @Test
    @DisplayName("no other country is even asked for a taxpayer record")
    void onlyGuatemalaIsAsked() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);
        OmsService service = service(url);

        service.buildOmsContext(107L, 6101, "70012345678901", 1);   // below the range
        service.buildOmsContext(107L, 6401, "70012345678901", 1);   // above it

        verify(nativeQueries, never()).list(contains("ps_socios_fel"), any(), any());
    }

    @Test
    @DisplayName("the old method name still answers, so nothing broke during the migration")
    void theLegacyMethodNameDelegates() {
        stubDatabase(clubRow("CO"), 1, Map.of("id", 1, "email", "v@pricesmart.com"));
        String url = startServer(200, MEMBER_JSON);

        assertThat(service(url).buildOmsContext(107L, 6101, "70012345678901", 1))
                .containsKeys("club", "member", "user", "exchange_rate");
    }

    // ── Submitting an order ───────────────────────────────────────────────

    @Test
    @DisplayName("an order is posted with the token in the query and the country as the organization")
    void orderCarriesTokenAndOrganization() {
        List<String> organizations = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        String url = startServer(exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            organizations.add(exchange.getRequestHeaders().getFirst("Organization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"orderId\":\"SO-9001\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });

        String response = service(url).submitOrder(Map.of("OrderLine", List.of()), "CO", "tok-123");

        assertThat(response).isEqualTo("{\"orderId\":\"SO-9001\"}");
        assertThat(received).containsExactly("POST /api/orders?access_token=tok-123");
        // OMS routes by organization, not by anything in the payload.
        assertThat(organizations).containsExactly("PriceSmartCO");
        assertThat(bodies).containsExactly("{\"OrderLine\":[]}");
    }

    @Test
    @DisplayName("a refusal is returned as the body it arrived in, not raised")
    void refusalIsReturnedNotRaised() {
        String url = startServer(400, "{\"errors\":[{\"message\":\"membership blocked\"}]}");

        String response = service(url).submitOrder(Map.of(), "CR", "tok-123");

        // The error stream, read the same way as a success: the caller decides.
        assertThat(response).contains("membership blocked");
    }

    @Test
    @DisplayName("an unreachable OMS comes back as an error object, so the caller still gets JSON")
    void unreachableOmsReturnsAnErrorObject() {
        String response = service(deadUrl()).submitOrder(Map.of(), "CR", "tok-123");

        assertThat(response).startsWith("{\"error\":").contains("Connection refused");
    }

    @Test
    @DisplayName("the old send method still delegates to the new one")
    void sendPayloadDelegates() {
        String url = startServer(200, "{\"orderId\":1}");

        assertThat(service(url).submitOrder(Map.of(), "CR", "tok-123")).isEqualTo("{\"orderId\":1}");
    }

    // ── Status history ────────────────────────────────────────────────────

    @Test
    @DisplayName("the status history is fetched by order number, with the token in the query")
    void historyIsFetchedByOrderNumber() {
        String url = startServer(200, "{\"status\":\"DELIVERED\"}");

        String response = service(url).getOrderStatusHistory("SO-9001", "tok-123");

        assertThat(response).isEqualTo("{\"status\":\"DELIVERED\"}");
        assertThat(received).containsExactly("GET /api/status/history/SO-9001?access_token=tok-123");
    }

    @Test
    @DisplayName("an error answer is returned as its body rather than raised")
    void historyErrorIsReturned() {
        String url = startServer(500, "{\"error\":\"upstream\"}");

        assertThat(service(url).getOrderStatusHistory("SO-9001", "tok-123")).contains("upstream");
    }

    @Test
    @DisplayName("an unreachable OMS yields an error object here too")
    void unreachableHistoryReturnsAnErrorObject() {
        assertThat(service(deadUrl()).getOrderStatusHistory("SO-9001", "tok-123"))
                .startsWith("{\"error\":");
    }
}
