package com.dqs.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advice, on the paths the controller tests do not reach.
 *
 * Its whole point is that a client error must not be reported as a server
 * fault. Spring MVC raises a family of exceptions that already carry the right
 * status — 405, 415, anything thrown as a ResponseStatusException — and they
 * implement an interface rather than sharing a superclass, so the check that
 * catches them is easy to lose. These tests are what would notice.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a typo in a URL is a 404, not an internal error")
    void unmappedPathIsNotFound() {
        ResponseEntity<Map<String, String>> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/v1/quotationz", "quotationz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Not found");
    }

    @Test
    @DisplayName("the wrong HTTP verb keeps its own 405 instead of becoming a 500")
    void wrongVerbKeepsItsStatus() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a deliberate status exception keeps its status, but not the reason it was given")
    void responseStatusExceptionKeepsItsStatusNotItsReason() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "send JSON"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // Worth pinning rather than changing here: the advice reads
        // ProblemDetail.getTitle(), which is the status reason phrase. A reason
        // the caller supplied lands in getDetail() and is dropped, so nobody
        // should expect a hand-written message to reach the client this way.
        assertThat(response.getBody()).containsEntry("error", "Unsupported Media Type");
    }

    @Test
    @DisplayName("a status exception with no reason falls back to naming its status")
    void responseStatusExceptionWithoutAReason() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isNotBlank();
    }

    /**
     * A hand-rolled ErrorResponse on a non-standard status, which is the only
     * way to reach the two null guards below.
     *
     * Every exception Spring raises fills in a body, and ProblemDetail resolves
     * a missing title from the status — but only for a code it knows. 599 is not
     * one, so the title really is null and the fallback runs. The guards exist
     * for an implementation we write ourselves later, so they are worth keeping
     * and worth covering.
     */
    private static final class BareErrorResponse extends RuntimeException implements ErrorResponse {
        private final ProblemDetail body;

        private BareErrorResponse(ProblemDetail body) {
            this.body = body;
        }

        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(599); }
        @Override public ProblemDetail getBody() { return body; }
    }

    @Test
    @DisplayName("an error response with no title at all falls back to naming its status")
    void errorResponseWithoutATitle() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new BareErrorResponse(ProblemDetail.forStatus(599)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(599));
        assertThat(response.getBody()).containsEntry("error", "599");
    }

    @Test
    @DisplayName("an error response with no body at all keeps its status rather than becoming a 500")
    void errorResponseWithoutABody() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(new BareErrorResponse(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(599));
        assertThat(response.getBody()).containsEntry("error", "599");
    }

    @Test
    @DisplayName("anything genuinely unexpected is a 500 that says nothing about internals")
    void unexpectedFailureIsAnOpaque500() {
        ResponseEntity<Map<String, String>> response = handler.handleGeneric(
                new IllegalStateException("connection pool exhausted at jdbc:mysql://dev-host/quotes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // The message stays in the log, not in the response: it names a host.
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
    }

    @Test
    @DisplayName("an upstream failure carries the status and path it came from")
    void businessApiExceptionCarriesItsContext() {
        BusinessApiException ex = new BusinessApiException(503, "/members/70012345678901", "unavailable");

        assertThat(ex.getStatus()).isEqualTo(503);
        assertThat(ex.getPath()).isEqualTo("/members/70012345678901");
        assertThat(ex.getBody()).isEqualTo("unavailable");
        assertThat(ex).hasMessage("Business API 503 on /members/70012345678901");
    }
}
