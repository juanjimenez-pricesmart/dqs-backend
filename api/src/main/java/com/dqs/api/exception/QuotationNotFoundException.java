// exception/QuotationNotFoundException.java
package com.dqs.api.exception;

public class QuotationNotFoundException extends RuntimeException {
    public QuotationNotFoundException(Long id) {
        super("Quotation not found: " + id);
    }
}