package com.dqs.api.service;

import com.dqs.api.dto.BankTransferNoticeResponse;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationTotals;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.support.NativeQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The bank-transfer minimum notice (B2B-676).
 *
 * Legacy shows a non-blocking warning under the payment method when Bank
 * Transfer is chosen and the quote total falls under the country's threshold —
 * OrdersService::validateBankTransferAmount, reached from
 * orders/checkbanktransferamount. It warns and nothing else: the quote can still
 * be closed, which is why this returns a verdict rather than throwing.
 *
 * The threshold lives in the legacy `ps_monto_minimo_cotizacion`, and the
 * country comes off the legacy `ps_tienda`, so both are read through
 * NativeQueries — they belong to the application being retired and must not be
 * mapped as entities (see repository/CLAUDE.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransferNoticeService {

    /** Bank Transfer. Legacy's OrdersService::BANK_TRANSFER_TENDER_KEY. */
    private static final String BANK_TRANSFER_TENDER_KEY = "110";

    private final QuotationRepository quotationRepository;
    private final NativeQueries nativeQueries;

    @Transactional(readOnly = true)
    public BankTransferNoticeResponse check(Long quotationId, String paymentMethod) {
        Quotation quotation = quotationRepository.findById(quotationId)
            .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        // net_amount already includes the 888905 transport line, which is what
        // the legacy message means by "incluyendo el transporte".
        QuotationTotals totals = quotation.getTotals();
        BigDecimal totalLocal = totals == null || totals.getNetAmount() == null
            ? BigDecimal.ZERO
            : totals.getNetAmount();

        if (!BANK_TRANSFER_TENDER_KEY.equals(paymentMethod)) {
            return BankTransferNoticeResponse.notApplicable(totalLocal);
        }

        Optional<Map<String, Object>> threshold = nativeQueries.first(
            "SELECT m.moneda AS currency, " +
            "       m.monto_notificacion_transferencia_local AS minimumLocal " +
            "FROM ps_tienda t " +
            // The two pais_iso2 columns carry different collations —
            // utf8mb4_0900_ai_ci on ps_tienda, utf8mb4_unicode_ci on the
            // minimums table — and a bare `=` between them is rejected outright
            // ("Illegal mix of collations"). Same conversion the catalogs import
            // uses for every legacy text join.
            "JOIN ps_monto_minimo_cotizacion m " +
            "  ON CONVERT(m.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
            "   = CONVERT(t.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci " +
            " AND m.activo = 1 " +
            "WHERE t.ps_tienda_id = ?1",
            quotation.getStoreId());

        // A country nobody configured, or one whose local threshold is null,
        // says nothing at all — legacy returns skipped:true for both.
        BigDecimal minimumLocal = threshold
            .map(row -> decimal(row.get("minimumLocal")))
            .orElse(null);
        if (minimumLocal == null) {
            log.info("[BankTransferNoticeService] no threshold for quotationId={} storeId={}",
                     quotationId, quotation.getStoreId());
            return BankTransferNoticeResponse.notApplicable(totalLocal);
        }

        boolean valid = totalLocal.compareTo(minimumLocal) >= 0;
        log.info("[BankTransferNoticeService] quotationId={} total={} minimum={} valid={}",
                 quotationId, totalLocal, minimumLocal, valid);

        return new BankTransferNoticeResponse(
            true, valid, totalLocal, minimumLocal,
            String.valueOf(threshold.get().get("currency")));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
