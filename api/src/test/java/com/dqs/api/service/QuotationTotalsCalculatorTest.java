package com.dqs.api.service;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationDelivery;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.model.QuotationItemTaxes;
import com.dqs.api.model.QuotationTotals;
import com.dqs.api.repository.QuotationDeliveryRepository;
import com.dqs.api.repository.QuotationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The totals are the money the member is charged, so each country rule gets its
 * own case rather than one test over a representative store.
 */
@ExtendWith(MockitoExtension.class)
class QuotationTotalsCalculatorTest {

    private static final int COLOMBIA   = 6101;
    private static final int GUATEMALA  = 6301;  // tax-inclusive
    private static final int COSTA_RICA = 6409;  // tax added on top
    private static final int NICARAGUA  = 8901;  // charges no IVA at all

    @Mock  private QuotationDeliveryRepository deliveryRepository;
    @Mock  private QuotationRepository quotationRepository;
    @InjectMocks private QuotationTotalsCalculator calculator;

    // ── helpers ──────────────────────────────────────────────────────────────

    private Quotation quotation(int storeId, QuotationItem... items) {
        Quotation q = Quotation.builder().id(1L).storeId(storeId).build();
        q.setTotals(QuotationTotals.builder().quotation(q).build());
        q.setItems(new ArrayList<>(List.of(items)));
        return q;
    }

    private QuotationItem line(String productId, String qty, String rate,
                               String taxFactor, String taxIco, String exempt) {
        QuotationItem item = QuotationItem.builder()
            .productId(productId)
            .qty(new BigDecimal(qty))
            .rate(new BigDecimal(rate))
            .build();
        item.setTaxes(QuotationItemTaxes.builder()
            .item(item)
            .taxFactor(new BigDecimal(taxFactor))
            .taxIco(new BigDecimal(taxIco))
            .exemptionAmount(new BigDecimal(exempt))
            .build());
        return item;
    }

    private void noDelivery() {
        when(deliveryRepository.findByQuotation_Id(any())).thenReturn(Optional.empty());
    }

    // ── country rules ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Colombia: the rate already carries the tax, so net equals gross")
    void colombiaKeepsTaxInsideTheRate() {
        noDelivery();
        Quotation q = quotation(COLOMBIA, line("508228", "2", "1000", "190", "50", "0"));

        calculator.recalculate(q);

        assertThat(q.getTotals().getGrossAmount()).isEqualByComparingTo("2000");
        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("tax-inclusive country: net equals gross, the tax is not added again")
    void vatInclusiveDoesNotAddTaxOnTop() {
        noDelivery();
        Quotation q = quotation(GUATEMALA, line("508228", "1", "1000", "120", "0", "0"));

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("elsewhere the rate is net, so the tax is added on top")
    void taxIsAdditiveWhereTheRateIsNet() {
        noDelivery();
        Quotation q = quotation(COSTA_RICA, line("508228", "3", "100", "13", "0", "0"));

        calculator.recalculate(q);

        assertThat(q.getTotals().getGrossAmount()).isEqualByComparingTo("300");
        // 3 × 100 + 3 × 13
        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("339");
    }

    @Test
    @DisplayName("a country that charges no IVA sums no tax, whatever the lines carry")
    void noIvaCountrySumsNoTax() {
        noDelivery();
        Quotation q = quotation(NICARAGUA, line("508228", "2", "100", "15", "0", "0"));

        calculator.recalculate(q);

        // the taxFactor on the line is ignored: 2 × 100 and nothing else
        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("200");
    }

    // ── the pieces that move the total ───────────────────────────────────────

    @Test
    @DisplayName("an exemption reduces what the member pays")
    void exemptionComesOffTheTotal() {
        noDelivery();
        Quotation q = quotation(COSTA_RICA, line("508228", "1", "100", "13", "0", "20"));

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("93");
    }

    @Test
    @DisplayName("delivery is added once, from its row, never from its 888905 line")
    void deliveryIsCountedOnceFromItsOwnRow() {
        when(deliveryRepository.findByQuotation_Id(any())).thenReturn(Optional.of(
            QuotationDelivery.builder().amount(new BigDecimal("500")).build()));

        Quotation q = quotation(COSTA_RICA,
            line("508228", "1", "100", "0", "0", "0"),
            line("888905", "1", "500", "0", "0", "0"));   // the delivery line

        calculator.recalculate(q);

        // 100 from the regular line + 500 from the delivery row, not 1100
        assertThat(q.getTotals().getGrossAmount()).isEqualByComparingTo("100");
        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("600");
        assertThat(q.getTotals().getDeliveryAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("a line with no tax row is summed for its rate and skipped for tax")
    void lineWithoutTaxesStillCounts() {
        noDelivery();
        QuotationItem bare = QuotationItem.builder()
            .productId("508228").qty(new BigDecimal("2")).rate(new BigDecimal("50")).build();
        Quotation q = quotation(COSTA_RICA, bare);

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("null amounts are read as zero rather than blowing up")
    void nullsAreTreatedAsZero() {
        noDelivery();
        QuotationItem item = QuotationItem.builder().productId("508228").build();
        item.setTaxes(QuotationItemTaxes.builder().item(item).build());
        Quotation q = quotation(COSTA_RICA, item);

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("no items at all leaves every figure at zero")
    void emptyQuotationIsZero() {
        noDelivery();
        Quotation q = quotation(COSTA_RICA);

        calculator.recalculate(q);

        assertThat(q.getTotals().getGrossAmount()).isEqualByComparingTo("0");
        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("0");
        assertThat(q.getTotals().getDeliveryAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a null item list is not an error")
    void nullItemListIsTolerated() {
        noDelivery();
        Quotation q = Quotation.builder().id(1L).storeId(COSTA_RICA).build();
        q.setTotals(QuotationTotals.builder().quotation(q).build());
        q.setItems(null);

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a store id of null falls back to the additive rule")
    void nullStoreIdIsTreatedAsUnknownCountry() {
        noDelivery();
        Quotation q = Quotation.builder().id(1L).storeId(null).build();
        q.setTotals(QuotationTotals.builder().quotation(q).build());
        q.setItems(new ArrayList<>(List.of(line("508228", "1", "100", "10", "0", "0"))));

        calculator.recalculate(q);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("110");
    }

    // ── guards ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a quotation with no totals row is left alone instead of failing")
    void missingTotalsRowIsSkipped() {
        Quotation q = Quotation.builder().id(1L).storeId(COLOMBIA).build();
        q.setTotals(null);

        calculator.recalculate(q);

        assertThat(q.getTotals()).isNull();
        verify(deliveryRepository, never()).findByQuotation_Id(any());
    }

    // ── the entry point callers use ──────────────────────────────────────────

    @Test
    @DisplayName("recalculateFor loads, recomputes and saves")
    void recalculateForSavesTheQuotation() {
        noDelivery();
        Quotation q = quotation(COSTA_RICA, line("508228", "1", "100", "13", "0", "0"));
        when(quotationRepository.findById(1L)).thenReturn(Optional.of(q));

        calculator.recalculateFor(1L);

        assertThat(q.getTotals().getNetAmount()).isEqualByComparingTo("113");
        verify(quotationRepository).save(q);
    }

    @Test
    @DisplayName("recalculateFor on a quotation that is gone saves nothing")
    void recalculateForUnknownQuotationDoesNothing() {
        when(quotationRepository.findById(99L)).thenReturn(Optional.empty());

        calculator.recalculateFor(99L);

        verify(quotationRepository, never()).save(any());
    }
}
