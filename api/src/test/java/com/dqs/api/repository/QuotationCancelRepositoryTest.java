package com.dqs.api.repository;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationCancelReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing a quotation without a sale.
 *
 * The reason ids here are the legacy ones — 1, 2, 4 and 5, with no 3 — because
 * the frontend labels them from i18n by id. A renumbering would silently
 * relabel every cancellation already on record, which is why the migration
 * moved the rows rather than the codes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationCancelRepositoryTest {

    @Mock private QuotationCancelReasonRepository reasonRepository;
    @Mock private QuotationRepository quotationRepository;

    private QuotationCancelRepository repository() {
        return new QuotationCancelRepository(reasonRepository, quotationRepository);
    }

    private QuotationCancelReason reason(int id, String description) {
        return QuotationCancelReason.builder().id(id).description(description).build();
    }

    @Test
    @DisplayName("the reasons come back as id and description, in id order")
    void reasonsComeBackAsIdAndDescription() {
        when(reasonRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                reason(1, "Existencias insuficientes"),
                reason(2, "El cliente no acepta"),
                reason(4, "Otro"),
                reason(5, "Cerrado por precio")));

        List<Map<String, Object>> reasons = repository().findReasons();

        // The frontend labels these from i18n by id, so the id is the contract
        // and the description is only the fallback.
        assertThat(reasons).containsExactly(
                Map.of("id", 1, "description", "Existencias insuficientes"),
                Map.of("id", 2, "description", "El cliente no acepta"),
                Map.of("id", 4, "description", "Otro"),
                Map.of("id", 5, "description", "Cerrado por precio"));
    }

    @Test
    @DisplayName("an empty catalog is an empty list")
    void anEmptyCatalogIsAnEmptyList() {
        when(reasonRepository.findAllByOrderByIdAsc()).thenReturn(List.of());

        assertThat(repository().findReasons()).isEmpty();
    }

    @Test
    @DisplayName("cancelling moves the quote to status four and records why")
    void cancellingRecordsTheReason() {
        Quotation quotation = Quotation.builder().id(107L).statusId(1).build();
        when(quotationRepository.findById(107L)).thenReturn(Optional.of(quotation));

        repository().cancel(107L, 4);

        assertThat(quotation.getStatusId()).isEqualTo(4);
        assertThat(quotation.getCancelReasonId()).isEqualTo(4);
        verify(quotationRepository).save(quotation);
    }

    @Test
    @DisplayName("cancelling a quotation that does not exist is refused")
    void anUnknownQuotationIsRefused() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository().cancel(404L, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quotation not found: 404");
        verify(quotationRepository, never()).save(any());
    }
}
