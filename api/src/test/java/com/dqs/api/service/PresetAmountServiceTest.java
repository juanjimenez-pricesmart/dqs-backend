package com.dqs.api.service;

import com.dqs.api.dto.PresetAmountResponse;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gift card's preset amounts.
 *
 * What matters here is that the wrong number never reaches a quotation line.
 * The denominations are USD and the price is local, and they are wildly
 * different figures — Colombia's $20 card is 77,500 COP — so putting the
 * denomination on the line would sell a card for twenty pesos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresetAmountServiceTest {

    @Mock private NativeQueries nativeQueries;

    private PresetAmountService service() {
        return new PresetAmountService(nativeQueries);
    }

    /** A row shaped as the join returns it, column labels and all. */
    private Map<String, Object> row(String usd, String local, int order, String code, String symbol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("denomination_usd", new BigDecimal(usd));
        m.put("local_amount",     new BigDecimal(local));
        m.put("display_order",    order);
        m.put("currency_code",    code);
        m.put("currency_symbol",  symbol);
        return m;
    }

    private void club(String iso2) {
        when(nativeQueries.scalar(contains("FROM ps_tienda"), eq(String.class), anyInt()))
                .thenReturn(Optional.ofNullable(iso2));
    }

    private void amounts(List<Map<String, Object>> rows) {
        when(nativeQueries.list(contains("FROM preset_amounts"), any(), any())).thenReturn(rows);
    }

    @Test
    @DisplayName("the gift card's three denominations come back in the order the table sets")
    void theThreeDenominationsComeBackInOrder() {
        club("CR");
        amounts(List.of(
                row("20.00",  "10000.00", 1, "CRC", "₡"),
                row("50.00",  "25000.00", 2, "CRC", "₡"),
                row("100.00", "50000.00", 3, "CRC", "₡")));

        List<PresetAmountResponse> result = service().getForProductAndClub("999979", 6401);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(PresetAmountResponse::getDenominationUsd)
                .containsExactly(new BigDecimal("20.00"), new BigDecimal("50.00"), new BigDecimal("100.00"));
        // The local amount is the one that becomes the line's price.
        assertThat(result).extracting(PresetAmountResponse::getLocalAmount)
                .containsExactly(new BigDecimal("10000.00"), new BigDecimal("25000.00"), new BigDecimal("50000.00"));
        assertThat(result.get(0).getCurrencySymbol()).isEqualTo("₡");
    }

    @Test
    @DisplayName("the local amount is priced by the business, not converted from the denomination")
    void theLocalAmountIsNotAConversion() {
        club("CO");
        amounts(List.of(row("20.00", "77500.00", 1, "COP", "$")));

        // 77,500 COP is not $20 at any rate anyone would quote; the figure is
        // set per country in preset_amounts. Nothing here may compute it.
        assertThat(service().getForProductAndClub("999979", 6101).get(0).getLocalAmount())
                .isEqualTo(new BigDecimal("77500.00"));
    }

    @Test
    @DisplayName("a product that does not use preset amounts never reaches the database")
    void anOrdinaryProductIsAnsweredWithoutAQuery() {
        assertThat(service().getForProductAndClub("1001", 6401)).isEmpty();

        // SpecialItems decides this, so an ordinary code costs no round trip.
        verify(nativeQueries, never()).scalar(any(), any(), any());
        verify(nativeQueries, never()).list(any(), any(), any());
    }

    @Test
    @DisplayName("the delivery SKU has no preset amounts either — its price is calculated")
    void deliveryHasNoPresetAmounts() {
        assertThat(service().getForProductAndClub("888905", 6401)).isEmpty();
    }

    @Test
    @DisplayName("a club with no country gets no amounts rather than somebody else's")
    void anUnknownClubGetsNothing() {
        club(null);

        assertThat(service().getForProductAndClub("999979", 9999)).isEmpty();
        // Legacy falls back to Costa Rica here, which silently offers Costa
        // Rican prices to a misconfigured club. Nothing is better than wrong.
        verify(nativeQueries, never()).list(contains("FROM preset_amounts"), any(), any());
    }

    @Test
    @DisplayName("a null club is answered without a query at all")
    void aNullClubIsAnsweredImmediately() {
        assertThat(service().getForProductAndClub("999979", null)).isEmpty();
        verify(nativeQueries, never()).list(any(), any(), any());
    }

    @Test
    @DisplayName("a country with nothing configured answers empty rather than failing")
    void aCountryWithNoAmountsIsEmpty() {
        club("PE");
        amounts(List.of());

        assertThat(service().getForProductAndClub("999979", 9001)).isEmpty();
    }

    @Test
    @DisplayName("the product and the club's country are what the query filters on")
    void theQueryFiltersOnProductAndCountry() {
        club("GT");
        amounts(List.of(row("20.00", "150.00", 1, "GTQ", "Q")));

        service().getForProductAndClub("999979", 6301);

        // The club resolves to a country first; the amounts are per country,
        // not per club, so two Guatemalan clubs must offer the same list.
        verify(nativeQueries).list(contains("FROM preset_amounts"), eq("999979"), eq("GT"));
    }

    @Test
    @DisplayName("a row missing its optional columns still maps")
    void aSparseRowStillMaps() {
        club("SV");
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("denomination_usd", new BigDecimal("20.00"));
        sparse.put("local_amount",     new BigDecimal("20.00"));
        sparse.put("display_order",    null);
        sparse.put("currency_code",    null);
        sparse.put("currency_symbol",  null);
        amounts(List.of(sparse));

        PresetAmountResponse only = service().getForProductAndClub("999979", 6501).get(0);

        assertThat(only.getLocalAmount()).isEqualTo(new BigDecimal("20.00"));
        assertThat(only.getDisplayOrder()).isNull();
        assertThat(only.getCurrencySymbol()).isNull();
    }
}
