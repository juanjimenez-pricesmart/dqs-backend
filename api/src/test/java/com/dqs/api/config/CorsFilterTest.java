package com.dqs.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The CORS filter, which is what lets the Vite dev server talk to this backend.
 *
 * It reflects the request's own Origin back, but only for localhost — so the
 * test that matters is the one proving it does NOT reflect an arbitrary origin.
 * Echoing any Origin with credentials allowed is how a page on another domain
 * reads a logged-in user's quotations.
 */
@ExtendWith(MockitoExtension.class)
class CorsFilterTest {

    @Mock private FilterChain chain;

    private final CorsFilter filter = new CorsFilter();

    private MockHttpServletResponse pass(String method, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/quotations");
        if (origin != null) request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a localhost origin is reflected back, whatever port Vite happens to be on")
    void localhostIsReflected() throws Exception {
        assertThat(pass("GET", "http://localhost:5173").getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:5173");
        assertThat(pass("GET", "http://localhost:4173").getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:4173");
    }

    @Test
    @DisplayName("any other origin is not reflected — that is the whole point of the check")
    void otherOriginsAreNotReflected() throws Exception {
        for (String origin : new String[] {
                "https://evil.example",
                "http://localhost.evil.example",     // the prefix check needs the colon
                "https://localhost:5173",            // https, not the dev server
                "http://127.0.0.1:5173" }) {
            assertThat(pass("GET", origin).getHeader("Access-Control-Allow-Origin"))
                    .as("origin %s", origin)
                    .isNull();
        }
    }

    @Test
    @DisplayName("a request with no Origin at all gets no reflection")
    void noOriginNoReflection() throws Exception {
        assertThat(pass("GET", null).getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("the allowed methods and headers go out regardless of the origin")
    void methodsAndHeadersAlwaysGoOut() throws Exception {
        MockHttpServletResponse response = pass("GET", "https://evil.example");

        assertThat(response.getHeader("Access-Control-Allow-Methods"))
                .isEqualTo("GET, POST, PUT, PATCH, DELETE, OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("*");
    }

    @Test
    @DisplayName("a preflight is answered here and does not reach the controllers")
    void preflightStopsAtTheFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/quotations");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        // Otherwise every OPTIONS would be a 405 from a controller that has no
        // handler for it.
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("a preflight is recognised whatever the case of the verb")
    void preflightIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("options", "/api/v1/quotations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("a real request carries on to the application")
    void realRequestsCarryOn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/quotations");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
