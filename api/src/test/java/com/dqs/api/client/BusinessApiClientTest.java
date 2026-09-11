package com.dqs.api.client;

import com.dqs.api.exception.BusinessApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Business API client.
 *
 * Two things here are worth defending. The access token travels as a query
 * parameter, so nothing may log the URL — and the URL builder has to avoid
 * doubling `/api` when the configured base already ends in it, which is a
 * mistake that produces a 404 nobody can read.
 *
 * And an upstream status must survive: a 404 from the membership service means
 * "no such member", not "our server broke". Flattening it is what once made a
 * missing membership read as "Internal server error".
 *
 * The constructor replaces the JVM's default SSL socket factory and hostname
 * verifier with ones that accept anything. These tests put both back
 * afterwards so the rest of the suite is not left weakened.
 */
class BusinessApiClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private SSLSocketFactory savedFactory;
    private HostnameVerifier savedVerifier;

    @BeforeEach
    void rememberTlsDefaults() {
        savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        savedVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    }

    @AfterEach
    void restore() {
        // Constructing the client turns off certificate and hostname checking
        // for the whole JVM; leaving that on would weaken every other test.
        HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory);
        HttpsURLConnection.setDefaultHostnameVerifier(savedVerifier);
        if (server != null) {
            server.stop(0);
            server = null;
        }
        requests.clear();
        bodies.clear();
    }

    private BusinessApiClient client(String businessUrl, String idpUrl) {
        BusinessApiClient c = new BusinessApiClient(new ObjectMapper());
        set(c, "businessBaseUrl", businessUrl);
        set(c, "idpBaseUrl", idpUrl);
        set(c, "clientId", "quotecenter");
        set(c, "clientSecret", "s3cr3t");
        return c;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = BusinessApiClient.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /**
     * A stand-in for both services: the token path answers `tokenBody`,
     * everything else answers `status` with `body`.
     */
    private String startServer(String tokenBody, int status, String body) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", (HttpExchange exchange) -> {
                String path = exchange.getRequestURI().toString();
                requests.add(exchange.getRequestMethod() + " " + path);
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean isToken = path.contains("openid-connect/token");
                byte[] out = (isToken ? tokenBody : body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(isToken ? 200 : status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
                exchange.close();
            });
            server.start();
            return "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String deadUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String TOKEN = "{\"access_token\":\"tok-123\"}";

    // ── The URL ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the token goes on the URL as a query parameter, which is why nothing logs it")
    void theTokenRidesOnTheUrl() {
        String url = startServer(TOKEN, 200, "{\"name\":\"JUAN PEREZ\"}");

        assertThat(client(url, url).get("/api/members/70012345678901")).contains("JUAN PEREZ");

        assertThat(requests).contains("GET /api/members/70012345678901?access_token=tok-123");
    }

    @Test
    @DisplayName("a base that already ends in /api does not get a second one")
    void theApiSegmentIsNotDoubled() {
        BusinessApiClient client = client("https://business.test/api", "https://idp.test");

        // Doubling it produces a 404 that reads like a missing member.
        assertThat(client.buildUrl("/api/members/70012345678901", "tok"))
                .isEqualTo("https://business.test/api/members/70012345678901?access_token=tok");
    }

    @Test
    @DisplayName("a base without /api keeps the path exactly as given")
    void aPlainBaseKeepsThePath() {
        assertThat(client("https://business.test", "https://idp.test")
                .buildUrl("/api/members/1", "tok"))
                .isEqualTo("https://business.test/api/members/1?access_token=tok");
    }

    @Test
    @DisplayName("trailing slashes on the base are trimmed, however many there are")
    void trailingSlashesAreTrimmed() {
        assertThat(client("https://business.test///", "https://idp.test")
                .buildUrl("/api/members/1", "tok"))
                .isEqualTo("https://business.test/api/members/1?access_token=tok");
    }

    @Test
    @DisplayName("a missing base or path builds a URL rather than throwing")
    void missingPartsStillBuild() {
        BusinessApiClient client = client(null, "https://idp.test");

        assertThat(client.buildUrl(null, "tok")).isEqualTo("?access_token=tok");
        assertThat(client.buildUrl("/api/members/1", "tok"))
                .isEqualTo("/api/members/1?access_token=tok");
    }

    @Test
    @DisplayName("a base ending in /api leaves a non-/api path alone")
    void anApiBaseLeavesOtherPathsAlone() {
        assertThat(client("https://business.test/api", "https://idp.test")
                .buildUrl("/members/1", "tok"))
                .isEqualTo("https://business.test/api/members/1?access_token=tok");
    }

    // ── GET ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an upstream 404 keeps its status, so a missing member is not a server fault")
    void anUpstream404KeepsItsStatus() {
        String url = startServer(TOKEN, 404, "{\"error\":\"not found\"}");

        assertThatThrownBy(() -> client(url, url).get("/api/members/70012345678901"))
                .isInstanceOf(BusinessApiException.class)
                .satisfies(e -> {
                    BusinessApiException ex = (BusinessApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(404);
                    assertThat(ex.getPath()).isEqualTo("/api/members/70012345678901");
                    // The body travels so the advice can decide what to say.
                    assertThat(ex.getBody()).contains("not found");
                });
    }

    @Test
    @DisplayName("any other upstream error keeps its status too")
    void otherUpstreamErrorsKeepTheirStatus() {
        String url = startServer(TOKEN, 503, "unavailable");

        assertThatThrownBy(() -> client(url, url).get("/api/members/1"))
                .isInstanceOf(BusinessApiException.class)
                .satisfies(e -> assertThat(((BusinessApiException) e).getStatus()).isEqualTo(503));
    }

    @Test
    @DisplayName("an upstream error with no body at all is reported with an empty one")
    void anEmptyErrorBodyIsSurvivable() {
        String url = startServer(TOKEN, 500, "");

        assertThatThrownBy(() -> client(url, url).get("/api/members/1"))
                .isInstanceOf(BusinessApiException.class);
    }

    @Test
    @DisplayName("a transport failure is a plain runtime error, not an upstream status")
    void aTransportFailureIsNotAnUpstreamStatus() {
        String idp = startServer(TOKEN, 200, "{}");

        // There is no status to preserve: the request never arrived.
        assertThatThrownBy(() -> client(deadUrl(), idp).get("/api/members/1"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(BusinessApiException.class)
                .hasMessageContaining("Business API error");
    }

    // ── POST ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a post sends its payload as JSON and returns the answer")
    void aPostSendsJson() {
        String url = startServer(TOKEN, 200, "{\"ok\":true}");

        assertThat(client(url, url).post("/api/quotes", Map.of("id", 107))).contains("ok");

        assertThat(requests).contains("POST /api/quotes?access_token=tok-123");
        assertThat(bodies).contains("{\"id\":107}");
    }

    @Test
    @DisplayName("an upstream error on a post keeps its status as well")
    void aPostKeepsUpstreamStatus() {
        String url = startServer(TOKEN, 422, "{\"error\":\"invalid\"}");

        assertThatThrownBy(() -> client(url, url).post("/api/quotes", Map.of()))
                .isInstanceOf(BusinessApiException.class)
                .satisfies(e -> assertThat(((BusinessApiException) e).getStatus()).isEqualTo(422));
    }

    @Test
    @DisplayName("a payload that cannot be serialised fails as our error, not the upstream's")
    void anUnserialisablePayloadIsOurError() {
        String url = startServer(TOKEN, 200, "{}");

        // A self-referencing object: Jackson cannot write it, and the request
        // never goes out.
        Map<String, Object> cycle = new java.util.HashMap<>();
        cycle.put("self", cycle);

        assertThatThrownBy(() -> client(url, url).post("/api/quotes", cycle))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(BusinessApiException.class)
                .hasMessageContaining("Business API error");
    }

    @Test
    @DisplayName("a transport failure on a post is a plain runtime error too")
    void aPostTransportFailureIsPlain() {
        String idp = startServer(TOKEN, 200, "{}");

        assertThatThrownBy(() -> client(deadUrl(), idp).post("/api/quotes", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(BusinessApiException.class);
    }

    // ── The token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the token is fetched with client credentials from the configured realm")
    void theTokenUsesClientCredentials() {
        String url = startServer(TOKEN, 200, "{}");

        assertThat(client(url, url).acquireToken()).isEqualTo("tok-123");

        assertThat(requests).contains("POST /auth/realms/PriceSmart/protocol/openid-connect/token");
        assertThat(bodies).contains("grant_type=client_credentials&client_id=quotecenter&client_secret=s3cr3t");
    }

    @Test
    @DisplayName("an identity provider that returns no token yields an empty one rather than a null")
    void aTokenlessAnswerYieldsAnEmptyToken() {
        String url = startServer("{\"error\":\"invalid_client\"}", 200, "{}");

        // The request then fails upstream on authorisation, which is a clearer
        // failure than a NullPointerException here.
        assertThat(client(url, url).acquireToken()).isEmpty();
    }

    @Test
    @DisplayName("an unreachable identity provider stops the call before it goes out")
    void anUnreachableIdpStopsTheCall() {
        assertThatThrownBy(() -> client("https://business.test", deadUrl()).get("/api/members/1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error adquiriendo token");
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a token answer that is not JSON is reported as a token failure")
    void anUnparseableTokenAnswerFails() {
        String url = startServer("<html>maintenance</html>", 200, "{}");

        assertThatThrownBy(() -> client(url, url).acquireToken())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error adquiriendo token");
    }
}
