package com.dqs.api.dto;

import java.math.BigDecimal;

/**
 * Whether a quote clears the bank-transfer minimum, and the figures behind it.
 *
 * No message: legacy formats one server-side with get_phrase, but this app
 * localises in the frontend, so the numbers travel and the wording is composed
 * there through i18n. Both locales then read correctly from one response.
 *
 * @param applies     false when the payment method is not bank transfer, or the
 *                    country has no configured threshold — the banner stays down
 *                    and `valid` carries no meaning
 * @param valid       total >= minimum. Advisory in legacy: it warns, never blocks
 * @param totalLocal  the quote's net amount, transport included
 * @param minimumLocal the country's notification threshold, in its own currency
 * @param currency    ISO code of both amounts
 */
public record BankTransferNoticeResponse(
    boolean    applies,
    boolean    valid,
    BigDecimal totalLocal,
    BigDecimal minimumLocal,
    String     currency
) {
    /** Nothing to say: wrong payment method, or a country without a threshold. */
    public static BankTransferNoticeResponse notApplicable(BigDecimal totalLocal) {
        return new BankTransferNoticeResponse(false, true, totalLocal, null, null);
    }
}
