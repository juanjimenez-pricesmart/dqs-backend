package com.dqs.api.exception;

/**
 * The quotation could not be emailed — sending is switched off, the member has
 * no usable address, or the relay refused it.
 *
 * Its own type so the screen can say which, rather than showing a 500 that
 * reads as an outage and invites a retry that will fail the same way.
 */
public class EmailNotSentException extends RuntimeException {

    public EmailNotSentException(String message) {
        super(message);
    }

    public EmailNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
