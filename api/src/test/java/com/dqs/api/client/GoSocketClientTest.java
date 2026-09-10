package com.dqs.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GoSocket client, which is what a fiscal-document validation goes through.
 *
 * The call is paid per request, so the frontend arms it deliberately rather
 * than on every keystroke — and this client is the last place the request can
 * be checked before it costs money. The URL it builds and the credentials it
 * sends are what these tests assert.
 */
class GoSocketClientTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        paths.clear();
        authorizations.clear();
    }

    private GoSocketClient client(String baseUrl) {
        GoSocketClient c = new GoSocketClient(new ObjectMapper());
        set(c, "baseUrl", baseUrl);
        set(c, "username", "quotecenter");
        set(c, "password", "s3cr3t");
        return c;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = GoSocketClient.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    private String startServer(int status, String body) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", (HttpExchange exchange) -> {
                paths.add(exchange.getRequestURI().toString());
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
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

    @Test
    @DisplayName("a validation carries all four parameters, and basic credentials")
    void aValidationCarriesEverything() {
        String url = startServer(200, "{\"valid\":true,\"name\":\"ACME SA\"}");

        Map<String, Object> result = client(url).getAccount("PS-CO", "NIT", "900123456", "CO");

        assertThat(result).containsEntry("valid", true).containsEntry("name", "ACME SA");
        assertThat(paths).containsExactly("/api/v1/Account/GetAccount"
                + "?AccountCode=PS-CO&identificationType=NIT&receiverCode=900123456&countryId=CO");
        assertThat(authorizations).containsExactly("Basic "
                + Base64.getEncoder().encodeToString("quotecenter:s3cr3t".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a refusal is read off the error stream and parsed, not raised as transport")
    void aRefusalIsParsed() {
        String url = startServer(404, "{\"valid\":false,\"message\":\"no existe\"}");

        // A taxpayer GoSocket does not know is an answer, not a failure.
        assertThat(client(url).getAccount("PS-CO", "NIT", "900999999", "CO"))
                .containsEntry("valid", false)
                .containsEntry("message", "no existe");
    }

    @Test
    @DisplayName("an answer that is not JSON is raised, because the caller has nothing to show")
    void anUnparseableAnswerIsRaised() {
        String url = startServer(200, "<html>maintenance</html>");

        assertThatThrownBy(() -> client(url).getAccount("PS-CO", "NIT", "900123456", "CO"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error validando identificación fiscal");
    }

    @Test
    @DisplayName("an unreachable provider is raised too, rather than reported as invalid")
    void anUnreachableProviderIsRaised() {
        // Reporting "not valid" on a network failure would tell the operator
        // the taxpayer's number is wrong when it is not.
        assertThatThrownBy(() -> client(deadUrl()).getAccount("PS-CO", "NIT", "900123456", "CO"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error validando identificación fiscal");
    }
}
