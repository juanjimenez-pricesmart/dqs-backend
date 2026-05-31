// exception/QuotationAlreadySubmittedException.java
package com.dqs.api.exception;

public class QuotationAlreadySubmittedException extends RuntimeException {
    public QuotationAlreadySubmittedException(Long id, Integer currentStatus) {
        super("Quotation " + id + " cannot be submitted — current status: " + currentStatus);
    }
}