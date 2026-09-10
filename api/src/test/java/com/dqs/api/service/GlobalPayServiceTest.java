package com.dqs.api.service;

import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationPayment;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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
 * Money changing hands: the token authorises the payment widget and the
 * callback is what marks a quote paid. A callback silently ignored leaves a
 * member charged against a quote that still reads unpaid, so every early return
 * gets a case.
 */
@ExtendWith(MockitoExtension.class)
class GlobalPayServiceTest {

    @Mock private NativeQueries nativeQueries;
    @Mock private QuotationRepository quotationRepository;
    @InjectMocks private GlobalPayService service;

    private void credentials(String appCode, String appKey) {
        Map<String, Object> row = new HashMap<>();
        row.put("server_appcode", appCode);
        row.put("server_appkey", appKey);
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of(row));
    }

    private Map<String, Object> callback(Object devReference, Object status) {
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("dev_reference", devReference);
        transaction.put("status", status);
        Map<String, Object> body = new HashMap<>();
        body.put("transaction", transaction);
        return body;
    }

    private Quotation quotationWithPayment() {
        Quotation q = Quotation.builder().id(107L).build();
        q.setPayment(QuotationPayment.builder().quotation(q).build());
        return q;
    }

    // ── token ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the token carries the app code and a hash, in the documented shape")
    void tokenHasTheDocumentedShape() {
        credentials("APPCODE", "APPKEY");

        Map<String, Object> out = service.getPaymentToken(6101);

        assertThat(out).containsEntry("storeId", 6101);
        String decoded = new String(Base64.getDecoder().decode(out.get("token").toString()),
                                    StandardCharsets.UTF_8);
        String[] parts = decoded.split(";");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("APPCODE");
        assertThat(parts[1]).matches("\\d+");            // unix seconds
        assertThat(parts[2]).matches("[0-9a-f]{64}");     // SHA-256, lower-case hex
    }

    @Test
    @DisplayName("a store with no credentials is refused rather than issued a broken token")
    void missingCredentialsAreRefused() {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getPaymentToken(6101))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("6101");
    }

    // ── callback ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status 4 marks the quote paid")
    void statusFourMarksPaid() {
        Quotation q = quotationWithPayment();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));

        service.processCallback(callback("107", 4));

        assertThat(q.getPayment().getPaidStatus()).isEqualTo(4);
        verify(quotationRepository).save(q);
    }

    @Test
    @DisplayName("any other status marks it failed, not paid")
    void otherStatusMarksFailed() {
        Quotation q = quotationWithPayment();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));

        service.processCallback(callback("107", 3));

        assertThat(q.getPayment().getPaidStatus()).isEqualTo(3);
    }

    @Test
    @DisplayName("a status arriving as text is still read")
    void statusAsTextIsRead() {
        Quotation q = quotationWithPayment();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));

        service.processCallback(callback("107", "4"));

        assertThat(q.getPayment().getPaidStatus()).isEqualTo(4);
    }

    @Test
    @DisplayName("a body with no transaction is ignored without touching anything")
    void bodyWithoutTransactionIsIgnored() {
        service.processCallback(new HashMap<>());

        verify(quotationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transaction with no reference is ignored")
    void missingReferenceIsIgnored() {
        service.processCallback(callback(null, 4));

        verify(quotationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transaction with no status is ignored")
    void missingStatusIsIgnored() {
        service.processCallback(callback("107", null));

        verify(quotationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reference that is not a quotation id is ignored, not guessed at")
    void nonNumericReferenceIsIgnored() {
        service.processCallback(callback("not-a-quote", 4));

        verify(quotationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a callback for a quotation that does not exist fails loudly")
    void unknownQuotationThrows() {
        when(quotationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processCallback(callback("999", 4)))
            .isInstanceOf(QuotationNotFoundException.class);
    }

    @Test
    @DisplayName("a quotation with no payment record is left alone")
    void quotationWithoutPaymentIsNotSaved() {
        Quotation q = Quotation.builder().id(107L).build();
        q.setPayment(null);
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(q));

        service.processCallback(callback("107", 4));

        verify(quotationRepository, never()).save(any());
    }
}
