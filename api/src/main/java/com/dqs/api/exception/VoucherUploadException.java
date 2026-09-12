package com.dqs.api.exception;

/**
 * The file the operator chose cannot be attached: wrong type, too large, or
 * storage is switched off in this deployment.
 *
 * Its own type so it can answer 400 without a blanket IllegalArgumentException
 * handler turning our own bugs into 400s as well.
 */
public class VoucherUploadException extends RuntimeException {

    public VoucherUploadException(String message) {
        super(message);
    }

    public VoucherUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
