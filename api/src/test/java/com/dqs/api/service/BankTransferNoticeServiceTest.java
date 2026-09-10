package com.dqs.api.service;

import com.dqs.api.dto.BankTransferNoticeResponse;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationTotals;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notice is advisory, so what matters is that it tells "below the minimum"
 * apart from "nothing to say" — a rejection and a country nobody configured
 * must not look the same to the panel.
 */
@ExtendWith(MockitoExtension.class)
class BankTransferNoticeServiceTest {

    private static final String BANK_TRANSFER = "110";
    private static final String PAYMENT_LINK  = "144";

    @Mock private QuotationRepository quotationRepository;
    @Mock private NativeQueries nativeQueries;
    @InjectMocks private BankTransferNoticeService service;

    private Quotation quotationWithTotal(String netAmount) {
        Quotation q = Quotation.builder().id(7L).storeId(6101).build();
        q.setTotals(QuotationTotals.builder()
            .quotation(q)
            .netAmount(netAmount == null ? null : new BigDecimal(netAmount))
            .build());
        return q;
    }

    private void thresholdOf(String minimum, String currency) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("currency", currency);
        row.put("minimumLocal", minimum == null ? null : new BigDecimal(minimum));
        when(nativeQueries.first(anyString(), any())).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("below the country minimum: applies, and not valid")
    void warnsBelowMinimum() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("129900")));
        thresholdOf("810000", "COP");

        BankTransferNoticeResponse r = service.check(7L, BANK_TRANSFER);

        assertThat(r.applies()).isTrue();
        assertThat(r.valid()).isFalse();
        assertThat(r.totalLocal()).isEqualByComparingTo("129900");
        assertThat(r.minimumLocal()).isEqualByComparingTo("810000");
        assertThat(r.currency()).isEqualTo("COP");
    }

    @Test
    @DisplayName("exactly the minimum clears it")
    void equalToMinimumIsValid() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("810000")));
        thresholdOf("810000", "COP");

        assertThat(service.check(7L, BANK_TRANSFER).valid()).isTrue();
    }

    @Test
    @DisplayName("above the minimum clears it")
    void aboveMinimumIsValid() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("900000")));
        thresholdOf("810000", "COP");

        assertThat(service.check(7L, BANK_TRANSFER).valid()).isTrue();
    }

    @Test
    @DisplayName("any other payment method says nothing and never reads the table")
    void otherPaymentMethodDoesNotApply() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("100")));

        BankTransferNoticeResponse r = service.check(7L, PAYMENT_LINK);

        assertThat(r.applies()).isFalse();
        assertThat(r.valid()).isTrue();
        assertThat(r.minimumLocal()).isNull();
        assertThat(r.currency()).isNull();
        verify(nativeQueries, never()).first(anyString(), any());
    }

    @Test
    @DisplayName("a country with no configured threshold says nothing")
    void unconfiguredCountryDoesNotApply() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("100")));
        when(nativeQueries.first(anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.check(7L, BANK_TRANSFER).applies()).isFalse();
    }

    @Test
    @DisplayName("a configured country whose local threshold is null says nothing")
    void nullThresholdDoesNotApply() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("100")));
        thresholdOf(null, "CRC");

        assertThat(service.check(7L, BANK_TRANSFER).applies()).isFalse();
    }

    @Test
    @DisplayName("a threshold stored as a plain number is still read")
    void nonBigDecimalThresholdIsConverted() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("100")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("currency", "GTQ");
        row.put("minimumLocal", 1900.0d);      // as a JDBC driver may hand it over
        when(nativeQueries.first(anyString(), any())).thenReturn(Optional.of(row));

        BankTransferNoticeResponse r = service.check(7L, BANK_TRANSFER);

        assertThat(r.applies()).isTrue();
        assertThat(r.minimumLocal()).isEqualByComparingTo("1900");
    }

    @Test
    @DisplayName("a threshold of an unreadable type is treated as unconfigured")
    void unreadableThresholdDoesNotApply() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal("100")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("currency", "COP");
        row.put("minimumLocal", "not a number");
        when(nativeQueries.first(anyString(), any())).thenReturn(Optional.of(row));

        assertThat(service.check(7L, BANK_TRANSFER).applies()).isFalse();
    }

    @Test
    @DisplayName("a quotation with no totals row counts as zero rather than failing")
    void missingTotalsCountsAsZero() {
        Quotation q = Quotation.builder().id(7L).storeId(6101).build();
        q.setTotals(null);
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(q));
        thresholdOf("810000", "COP");

        BankTransferNoticeResponse r = service.check(7L, BANK_TRANSFER);

        assertThat(r.totalLocal()).isEqualByComparingTo("0");
        assertThat(r.valid()).isFalse();
    }

    @Test
    @DisplayName("a totals row with a null net amount counts as zero")
    void nullNetAmountCountsAsZero() {
        when(quotationRepository.findById(7L)).thenReturn(Optional.of(quotationWithTotal(null)));
        thresholdOf("810000", "COP");

        assertThat(service.check(7L, BANK_TRANSFER).totalLocal()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an unknown quotation is a 404, not a silent pass")
    void unknownQuotationThrows() {
        when(quotationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.check(99L, BANK_TRANSFER))
            .isInstanceOf(QuotationNotFoundException.class);
    }
}
