package com.dqs.api.service;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.model.QuotationItemTaxes;
import com.dqs.api.model.QuotationTotals;
import com.dqs.api.repository.QuotationDeliveryRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.util.ClubCapabilities;
import com.dqs.api.util.SpecialItems;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Derives a quotation's totals from its own lines.
 *
 * <h2>Why the server owns this</h2>
 *
 * Legacy has the browser add the money up and post the result — `frm_subtotal`,
 * `frm_descuento`, `frm_total` travel in the form that closes the quote, and
 * `Orders::update` stores whatever arrives. That makes the totals a claim by the
 * client rather than a fact about the lines, and it is why they are only ever
 * right at the moment of closing.
 *
 * We do not copy that. Totals are recomputed here from the persisted lines
 * whenever those lines change, so `quotation_totals` is always a function of
 * `quotation_items` and nothing can send a figure that disagrees with them. No
 * endpoint accepts a total.
 *
 * <h2>What it computes</h2>
 *
 * The same arithmetic the lines table displays (frontend `useTotals`), which is
 * the only definition anyone has agreed on:
 *
 * <pre>
 *   gross   = Σ qty × rate            over regular lines
 *   tax     = Σ qty × taxFactor       zero where the country charges no IVA
 *   ico     = Σ qty × taxIco          Colombia only
 *   exempt  = Σ excentAmount          reduces what the member pays
 *
 *   Colombia         net = gross - exempt + delivery      (rate is gross, tax inside)
 *   tax-inclusive    net = gross - exempt + delivery      (rate is gross)
 *   everywhere else  net = gross + tax - exempt + delivery (rate is net, tax added)
 * </pre>
 *
 * `grossAmount` keeps the sum of the lines as entered, so the two figures
 * together say what was charged and what it was built from.
 *
 * <h2>Special lines</h2>
 *
 * Delivery is item 888905 and also a `quotation_delivery` row. It is excluded
 * from the line sums by {@link SpecialItems#isRegularLine} and added once from
 * the delivery row, so it cannot be counted twice — the mistake that makes a
 * total look right until a delivery is added.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotationTotalsCalculator {

    private static final int MONEY_SCALE = 4;

    /**
     * Looked up rather than navigated: Quotation has no delivery association,
     * and adding one would put a lazy fetch on every load of a quotation for
     * the sake of one caller.
     */
    private final QuotationDeliveryRepository deliveryRepository;
    private final QuotationRepository quotationRepository;

    /**
     * The single entry point for callers that changed a quotation's lines or its
     * delivery: load, recompute, save. Every mutation ends with this one call
     * rather than repeating the arithmetic or the plumbing.
     */
    @Transactional
    public void recalculateFor(Long quotationId) {
        quotationRepository.findById(quotationId).ifPresentOrElse(
            quotation -> {
                recalculate(quotation);
                quotationRepository.save(quotation);
            },
            () -> log.warn("[QuotationTotalsCalculator] quotationId={} not found", quotationId));
    }

    /**
     * Recomputes and assigns the totals on an already-loaded quotation, for
     * callers that are mid-transaction and will save it themselves.
     */
    public void recalculate(Quotation quotation) {
        QuotationTotals totals = quotation.getTotals();
        if (totals == null) {
            log.warn("[QuotationTotalsCalculator] quotationId={} has no totals row to update",
                     quotation.getId());
            return;
        }

        int storeId = quotation.getStoreId() == null ? 0 : quotation.getStoreId();
        List<QuotationItem> lines = quotation.getItems() == null ? List.of() : quotation.getItems();

        BigDecimal gross  = BigDecimal.ZERO;
        BigDecimal tax    = BigDecimal.ZERO;
        BigDecimal ico    = BigDecimal.ZERO;
        BigDecimal exempt = BigDecimal.ZERO;

        boolean noIva  = ClubCapabilities.usesNoIva(storeId);
        boolean usesIco = ClubCapabilities.usesIco(storeId);

        for (QuotationItem line : lines) {
            if (!SpecialItems.isRegularLine(line.getProductId())) continue;

            BigDecimal qty = nz(line.getQty());
            gross = gross.add(qty.multiply(nz(line.getRate())));

            QuotationItemTaxes taxes = line.getTaxes();
            if (taxes == null) continue;

            if (!noIva)  tax = tax.add(qty.multiply(nz(taxes.getTaxFactor())));
            if (usesIco) ico = ico.add(qty.multiply(nz(taxes.getTaxIco())));
            exempt = exempt.add(nz(taxes.getExcentAmount()));
        }

        BigDecimal delivery = deliveryRepository.findByQuotation_Id(quotation.getId())
            .map(d -> nz(d.getAmount()))
            .orElse(BigDecimal.ZERO);

        // Where the rate already carries the tax, adding it again would charge
        // it twice; where it does not, the tax is what turns gross into net.
        boolean taxInsideRate = ClubCapabilities.isColombia(storeId)
                             || ClubCapabilities.isVatInclusive(storeId);

        BigDecimal net = taxInsideRate
            ? gross.subtract(exempt).add(delivery)
            : gross.add(tax).subtract(exempt).add(delivery);

        totals.setGrossAmount(scale(gross));
        totals.setNetAmount(scale(net));
        totals.setDeliveryAmount(scale(delivery));

        log.info("[QuotationTotalsCalculator] quotationId={} storeId={} gross={} tax={} ico={} exempt={} delivery={} net={}",
                 quotation.getId(), storeId, gross, tax, ico, exempt, delivery, net);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
