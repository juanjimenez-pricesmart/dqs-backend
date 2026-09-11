package com.dqs.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One selectable amount for a product sold at fixed denominations — today only
 * the gift card, 999979.
 *
 * Legacy returns `{value, label}` with the label already assembled in SQL:
 * CONCAT("$", denomination_usd, " USD (", currency_symbol, " ",
 * FORMAT(local_amount, 0), ")"). We return the parts instead. MySQL's FORMAT()
 * groups digits one fixed way, and the frontend has to put every number through
 * fmt(n, locale) — a label baked here would arrive already formatted for the
 * wrong country and there would be no way to reformat it.
 */
@Data
@Builder
public class PresetAmountResponse {

    /** The USD denomination the card is sold as: 20, 50, 100. */
    private BigDecimal denominationUsd;

    /** What the line is actually priced at, in the club's currency. */
    private BigDecimal localAmount;

    /** Legacy's pa.display_order — the order the dropdown is built in. */
    private Integer displayOrder;

    private String currencyCode;
    private String currencySymbol;
}
