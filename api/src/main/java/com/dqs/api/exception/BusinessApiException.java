package com.dqs.api.exception;

/**
 * A non-2xx response from the Business API.
 *
 * Carries the upstream status so the caller's failure is not flattened into a
 * generic 500. That flattening is what made a membership lookup report
 * "Internal server error" when the upstream had simply answered 404.
 */
public class BusinessApiException extends RuntimeException {

    private final int status;
    private final String path;
    private final String body;

    public BusinessApiException(int status, String path, String body) {
        super("Business API " + status + " on " + path);
        this.status = status;
        this.path = path;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getPath() {
        return path;
    }

    public String getBody() {
        return body;
    }
}
