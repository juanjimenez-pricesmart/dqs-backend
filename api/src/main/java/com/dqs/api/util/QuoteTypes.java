package com.dqs.api.util;

/**
 * How a closed quotation is fulfilled — legacy's {@code orders.quote_type},
 * our {@code quotation_payment.quote_type_id}.
 *
 * The values are legacy's and cannot be renumbered: the same column is read by
 * the legacy application while both run, and by the delivery-log rows that
 * carry a {@code quote_type} of their own.
 *
 * {@link #UNSET} is the state the "Tipo de cotización" select opens in.
 * {@code createquote()} refuses to close a sale while it is still there
 * (edit.php, {@code if (quoteTypeId == 0)}), which is why it is a real value
 * rather than a null.
 *
 * The frontend keeps the same three in {@code src/constants/quotation.ts}.
 */
public final class QuoteTypes {

    private QuoteTypes() {}

    /** Nothing chosen yet. */
    public static final int UNSET = 0;

    /** Fulfilled at the counter. Never offered to OMS. */
    public static final int FRONT_DESK = 1;

    /** Sent to OMS as an order. */
    public static final int OMS = 2;
}
