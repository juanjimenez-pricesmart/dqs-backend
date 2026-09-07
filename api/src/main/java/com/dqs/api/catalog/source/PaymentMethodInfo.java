package com.dqs.api.catalog.source;

/**
 * A payment method available in a country.
 *
 * {@code tenderKey} is the value that matters: the frontend's select carries it
 * as the option value and {@code quotation_payment.payment_method_id} stores it.
 * {@code id} is only an identity for the list — the frontend uses it as a React
 * key and nothing else — which is why it can differ between the two sources
 * without breaking anything.
 */
public record PaymentMethodInfo(
    Integer id,
    String  description,
    Integer tenderKey
) {}
