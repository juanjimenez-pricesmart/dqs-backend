package com.dqs.api.repository;

import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quotation list, which is one query assembled from the filters the screen
 * offers — and the reason these tests exist is that a clause that fails to
 * apply does not fail. It just shows the operator someone else's quotations, or
 * the wrong month's.
 *
 * So the assertions are on the SQL that was built and on the parameters that
 * went with it, in order. What they cannot say is whether MySQL accepts it;
 * that needs a real server with the legacy schema.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationListRepositoryTest {

    @Mock private NativeQueries nativeQueries;

    private QuotationListRepository repository() {
        return new QuotationListRepository(nativeQueries);
    }

    /** The SQL and parameters of the last list() call. */
    private record Call(String sql, Object[] params) {}

    private Call lastCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(nativeQueries, org.mockito.Mockito.atLeastOnce()).list(sql.capture(), params.capture());
        return new Call(sql.getAllValues().get(sql.getAllValues().size() - 1),
                params.getAllValues().get(params.getAllValues().size() - 1));
    }

    private void stubPeriod(Integer month, Integer year) {
        when(nativeQueries.list(contains("ps_cierre_mensual_mes, ps_cierre_mensual_anio"), any()))
                .thenReturn(month == null ? List.of()
                        : List.of(Map.of("ps_cierre_mensual_mes", month, "ps_cierre_mensual_anio", year)));
    }

    // ── The status and store filters ──────────────────────────────────────

    @Test
    @DisplayName("a pending list filters on the club and the status, and nothing else")
    void pendingFiltersOnClubAndStatusOnly() {
        repository().findByStoreFiltered(6101, 1, false, null, null);

        Call call = lastCall();
        assertThat(call.sql()).contains("q.store_id = ?").contains("q.status_id = ?");
        // Pending quotations are never period-filtered: an operator working a
        // quote from last month still needs to see it.
        assertThat(call.sql()).doesNotContain("MONTH(q.date_time)");
        assertThat(call.sql()).doesNotContain("q.user_id = ?");
        assertThat(call.params()).containsExactly(6101, 1);
    }

    @Test
    @DisplayName("any other status is filtered by period as well")
    void nonPendingFiltersByPeriod() {
        stubPeriod(9, 2026);

        repository().findByStoreFiltered(6101, 3, false, null, 42);

        Call call = lastCall();
        assertThat(call.sql()).contains("MONTH(q.date_time) = ?").contains("YEAR(q.date_time) = ?");
        // Month and year after the club and the status, in that order.
        assertThat(call.params()).containsExactly(6101, 3, 9, 2026);
    }

    @Test
    @DisplayName("a period nobody asked for resolves to month zero, which matches nothing")
    void anAbsentPeriodResolvesToZero() {
        repository().findByStoreFiltered(6101, 3, false, null, null);

        // Deliberate: without a period the list is empty rather than showing
        // every closed quotation the club has ever had.
        assertThat(lastCall().params()).containsExactly(6101, 3, 0, 0);
    }

    @Test
    @DisplayName("a period id that is not in the table resolves the same way")
    void anUnknownPeriodResolvesToZero() {
        stubPeriod(null, null);

        repository().findByStoreFiltered(6101, 3, false, null, 999);

        assertThat(lastCall().params()).containsExactly(6101, 3, 0, 0);
    }

    @Test
    @DisplayName("the period is looked up by its id, not computed from today")
    void thePeriodIsLookedUp() {
        stubPeriod(2, 2025);

        repository().findByStoreFiltered(6101, 4, false, null, 7);

        verify(nativeQueries).list(contains("WHERE ps_cierre_mensual_id = ?"), org.mockito.ArgumentMatchers.eq(7));
        assertThat(lastCall().params()).containsExactly(6101, 4, 2, 2025);
    }

    // ── The scope filter ──────────────────────────────────────────────────

    @Test
    @DisplayName("\"mine\" narrows the list to one user")
    void mineNarrowsToOneUser() {
        repository().findByStoreFiltered(6101, 1, true, 7, null);

        Call call = lastCall();
        assertThat(call.sql()).contains("q.user_id = ?");
        assertThat(call.params()).containsExactly(6101, 1, 7);
    }

    @Test
    @DisplayName("\"mine\" without a user id does not narrow at all")
    void mineWithoutAUserDoesNotNarrow() {
        repository().findByStoreFiltered(6101, 1, true, null, null);

        // Better the whole club than a clause on a null, which would match
        // nothing and read as "you have no quotations".
        Call call = lastCall();
        assertThat(call.sql()).doesNotContain("q.user_id = ?");
        assertThat(call.params()).containsExactly(6101, 1);
    }

    @Test
    @DisplayName("a user id without \"mine\" is ignored")
    void aUserIdAloneIsIgnored() {
        repository().findByStoreFiltered(6101, 1, false, 7, null);

        assertThat(lastCall().sql()).doesNotContain("q.user_id = ?");
        assertThat(lastCall().params()).containsExactly(6101, 1);
    }

    @Test
    @DisplayName("every filter at once keeps its parameters in the order the clauses were appended")
    void allFiltersKeepTheirOrder() {
        stubPeriod(9, 2026);

        repository().findByStoreFiltered(6101, 3, true, 7, 42);

        // club, status, month, year, user — the order the SQL reads.
        assertThat(lastCall().params()).containsExactly(6101, 3, 9, 2026, 7);
    }

    // ── The shape of the row ──────────────────────────────────────────────

    @Test
    @DisplayName("the amount falls back to the sum of the lines when the totals row has none")
    void amountFallsBackToTheLineSum() {
        repository().findByStoreFiltered(6101, 1, false, null, null);

        // Which is what kept the list readable while net_amount was never
        // being written; the fallback stays because old quotations still have
        // a null there.
        assertThat(lastCall().sql())
                .contains("COALESCE(qt.net_amount")
                .contains("SELECT COALESCE(SUM(qi.amount), 0)");
    }

    @Test
    @DisplayName("the row carries the item count and whether any line's price moved")
    void theRowCarriesCountAndVariation() {
        repository().findByStoreFiltered(6101, 1, false, null, null);

        String sql = lastCall().sql();
        assertThat(sql).contains("AS itemCount").contains("AS hasPriceVariation");
        // variacion = 1 is what highlights the row on the list.
        assertThat(sql).contains("qi.variacion = 1");
    }

    @Test
    @DisplayName("the customer joins are left joins, so a quote without one still lists")
    void theCustomerJoinsAreOptional() {
        repository().findByStoreFiltered(6101, 1, false, null, null);

        String sql = lastCall().sql();
        assertThat(sql).contains("LEFT JOIN quotation_customers")
                .contains("LEFT JOIN quotation_totals")
                .contains("LEFT JOIN quotation_payment")
                .contains("LEFT JOIN users");
    }

    @Test
    @DisplayName("the newest quotation comes first")
    void theNewestComesFirst() {
        repository().findByStoreFiltered(6101, 1, false, null, null);

        assertThat(lastCall().sql()).endsWith("ORDER BY q.id DESC");
    }

    @Test
    @DisplayName("whatever the query returned is handed back untouched")
    void rowsAreHandedBackUntouched() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 107));
        // The repository passes its parameters as one Object[], so the matcher
        // has to be for the array rather than for a single argument.
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(rows);

        assertThat(repository().findByStoreFiltered(6101, 1, false, null, null)).isSameAs(rows);
    }

    // ── The other three queries ───────────────────────────────────────────

    @Test
    @DisplayName("the pending deliveries are the sold quotations of one club that have no order yet")
    void pendingDeliveriesAreSoldQuotations() {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of());

        repository().findDeliveriesByStore(6101);

        Call call = lastCall();
        assertThat(call.sql()).contains("quotation_delivery");
        assertThat(call.params()).containsExactly(6101);
    }

    @Test
    @DisplayName("the summary counts and totals per status, with the period applied")
    void theSummaryGroupsByStatus() {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of());

        repository().findSummaryByStatus(6101, 42);

        assertThat(lastCall().sql()).contains("status_id");
    }

    @Test
    @DisplayName("the summary works with no period at all")
    void theSummaryWorksWithoutAPeriod() {
        when(nativeQueries.list(anyString(), any())).thenReturn(List.of());

        assertThat(repository().findSummaryByStatus(6101, null)).isEmpty();
    }

    @Test
    @DisplayName("the closing periods come newest first, with a label per language")
    void periodsCarryBothLabels() {
        when(nativeQueries.list(anyString())).thenReturn(List.of(Map.of("id", 42)));

        assertThat(repository().findPeriods()).containsExactly(Map.of("id", 42));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).list(sql.capture());
        // Spanish and English come from the same row, so the filter reads right
        // in either locale from one query.
        assertThat(sql.getValue()).contains("AS label").contains("AS labelEn")
                .contains("ORDER BY c.ps_cierre_mensual_id DESC");
    }
}
