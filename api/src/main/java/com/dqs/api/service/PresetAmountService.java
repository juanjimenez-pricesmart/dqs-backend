package com.dqs.api.service;

import com.dqs.api.dto.PresetAmountResponse;
import com.dqs.api.repository.support.NativeQueries;
import com.dqs.api.util.SpecialItems;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The fixed amounts a gift card can be sold at, per country.
 *
 * Legacy's PresetAmountsService (application/services/PresetAmountsService.php)
 * over three tables that predate QuoteCenter: `preset_amount_categories` maps a
 * product code to a category, `preset_amounts` holds one row per country and
 * USD denomination, and `countries` carries the currency. Thirteen countries,
 * three denominations each — $20, $50 and $100 — priced in local currency by
 * the business rather than converted, which is why Colombia's $20 is 77,500 COP
 * and Guatemala's is 150 GTQ.
 *
 * `preset_amounts` and `preset_amount_categories` belong to the application we
 * are replacing, so they are read through NativeQueries and never mapped as
 * entities. `countries` is ours and already has one, but the join runs in the
 * same statement rather than in two round trips.
 *
 * Nothing here is gift-card specific: the product code is a parameter and the
 * tables decide. 999979 is simply the only code configured today, and
 * SpecialItems.Trait.PRESET_AMOUNT is what marks it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetAmountService {

    private final NativeQueries nativeQueries;

    /**
     * Legacy's Model_orders equivalent is get_by_product_and_country, which
     * filters on pa/pac/c being active and orders by display_order then
     * denomination. Kept as one statement: the service's separate
     * has_preset_amounts() pre-check only decides which of two "no amounts"
     * messages to return, and we answer an empty list either way.
     */
    private static final String PRESET_AMOUNTS_SQL = """
        SELECT pa.denomination_usd, pa.local_amount, pa.display_order,
               c.currency_code, c.currency_symbol
          FROM preset_amounts pa
          JOIN preset_amount_categories pac ON pa.preset_category_id = pac.id
          JOIN countries c                  ON pa.country_id = c.id
         WHERE pac.product_id = ?
           AND c.code = ?
           AND pa.is_active = 1
           AND pac.is_active = 1
           AND c.is_active = 1
         ORDER BY pa.display_order, pa.denomination_usd
        """;

    /**
     * The amounts offered for a product at a club, in dropdown order.
     *
     * Empty when the product has no preset amounts, when the club is unknown,
     * or when the club's country has none configured. The caller cannot act on
     * the difference — all three mean there is no dropdown to show — so the
     * distinction is logged rather than encoded in the response. Legacy returns
     * a different message for each and the screen ignores every one of them.
     */
    public List<PresetAmountResponse> getForProductAndClub(String productId, Integer clubId) {
        if (!SpecialItems.has(productId, SpecialItems.Trait.PRESET_AMOUNT)) {
            log.debug("[PresetAmountService] productId={} has no preset amounts", productId);
            return List.of();
        }

        String countryCode = countryOf(clubId);
        if (countryCode == null || countryCode.isBlank()) {
            log.warn("[PresetAmountService] no country for clubId={}, no preset amounts", clubId);
            return List.of();
        }

        List<Map<String, Object>> rows = nativeQueries.list(PRESET_AMOUNTS_SQL, productId, countryCode);
        if (rows.isEmpty()) {
            log.warn("[PresetAmountService] productId={} country={} has no amounts configured",
                    productId, countryCode);
            return List.of();
        }

        log.info("[PresetAmountService] productId={} clubId={} country={} amounts={}",
                productId, clubId, countryCode, rows.size());
        return rows.stream().map(PresetAmountService::toResponse).toList();
    }

    /**
     * Straight from ps_tienda.
     *
     * Legacy takes a longer road — ClubCountries::getCountry(store_id) returns a
     * country *name*, which it then looks up in countries.country_name_full,
     * falling back to Costa Rica when either step fails. That fallback is why a
     * misconfigured club silently offers Costa Rican amounts there. Checked
     * against dev: pais_iso2 is a clean ISO2 for all 62 clubs and matches
     * countries.code for all thirteen countries, so the name round trip buys
     * nothing, and an unknown club gets no amounts rather than somebody else's.
     *
     * This stays a separate statement, and the country then travels as a bound
     * parameter. Do not fold it into the join above: ps_tienda.pais_iso2 is
     * utf8mb4_unicode_ci and countries.code is utf8mb4_0900_ai_ci, so
     * `ON c.code = t.pais_iso2` is rejected outright —
     *
     *   ERROR 1267 (HY000): Illegal mix of collations
     *   (utf8mb4_0900_ai_ci,IMPLICIT) and (utf8mb4_unicode_ci,IMPLICIT)
     *   for operation '='
     *
     * — because the two tables were created years apart by different teams.
     * Comparing the column to a parameter is fine; comparing the two columns is
     * not, and no amount of indexing makes it work without a COLLATE clause.
     */
    private String countryOf(Integer clubId) {
        if (clubId == null) return null;
        return nativeQueries
                .scalar("SELECT pais_iso2 FROM ps_tienda WHERE ps_tienda_id = ?", String.class, clubId)
                .orElse(null);
    }

    private static PresetAmountResponse toResponse(Map<String, Object> row) {
        return PresetAmountResponse.builder()
                .denominationUsd(decimal(row.get("denomination_usd")))
                .localAmount(decimal(row.get("local_amount")))
                .displayOrder(row.get("display_order") == null ? null
                        : ((Number) row.get("display_order")).intValue())
                .currencyCode(string(row.get("currency_code")))
                .currencySymbol(string(row.get("currency_symbol")))
                .build();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        return new BigDecimal(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
