package com.dqs.api.repository;

import com.dqs.api.model.QuotationDelivery;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery-log queries.
 *
 * The interesting one is the "available" list: it must return only deliveries
 * that are not on a log already AND are on the route being loaded. Drop either
 * condition and an operator loads a stop that is already on another truck, or
 * one from a different route.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryLogRepositoryTest {

    @Mock private NativeQueries nativeQueries;
    @Mock private QuotationDeliveryRepository deliveryRepository;

    private DeliveryLogRepository repository() {
        return new DeliveryLogRepository(nativeQueries, deliveryRepository);
    }

    private String lastSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries, org.mockito.Mockito.atLeastOnce()).list(sql.capture(), any(Object[].class));
        return sql.getAllValues().get(sql.getAllValues().size() - 1);
    }

    @Test
    @DisplayName("the available deliveries are the ones on no log yet and on this route")
    void availableExcludesLoadedAndOtherRoutes() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());

        repository().findAvailableDeliveries(6101, "6101 01");

        String sql = lastSql();
        // Both conditions matter: without the first an operator reloads a stop
        // already on another truck, without the second they load another route.
        assertThat(sql).contains("qd.logcargueid IS NULL").contains("qd.route_id = ?");
        assertThat(sql).contains("q.store_id = ?");
        verify(nativeQueries).list(contains("logcargueid IS NULL"), eq(6101), eq("6101 01"));
    }

    @Test
    @DisplayName("the available list is ordered by when each delivery is due")
    void availableIsOrderedByDueTime() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());

        repository().findAvailableDeliveries(6101, "6101 01");

        // Which is the order the truck is loaded in.
        assertThat(lastSql()).contains("ORDER BY qd.delivery_date, qd.hour_from");
    }

    @Test
    @DisplayName("the customer join is optional, so a delivery without one still lists")
    void theCustomerJoinIsOptional() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());

        repository().findAvailableDeliveries(6101, "6101 01");

        assertThat(lastSql()).contains("LEFT JOIN quotation_customers");
    }

    @Test
    @DisplayName("logs are found by club, status and route together")
    void logsAreFoundByAllThree() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());

        repository().findByStoreAndStatus(6101, 2, "6101 01");

        verify(nativeQueries).list(contains("ps_delivery_log_cargue"), eq(6101), eq(2), eq("6101 01"));
        assertThat(lastSql()).contains("COUNT(qd.id) AS delivery_count")
                .contains("GROUP BY l.logcargueid");
    }

    @Test
    @DisplayName("a log's own deliveries are read by its id")
    void deliveriesAreReadByLogId() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 1)));

        assertThat(repository().findDeliveriesByLogId(9L)).hasSize(1);
        verify(nativeQueries).list(contains("qd.logcargueid = ?"), eq(9L));
    }

    @Test
    @DisplayName("one log comes back as a row, and an unknown id as nothing")
    void oneLogOrNothing() {
        when(nativeQueries.list(contains("WHERE logcargueid = ?"), any(Object[].class)))
                .thenReturn(List.of(Map.of("logcargueid", 9L)));
        assertThat(repository().findById(9L)).containsEntry("logcargueid", 9L);

        when(nativeQueries.list(contains("WHERE logcargueid = ?"), any(Object[].class)))
                .thenReturn(List.of());
        // Null rather than an empty map: the PDF service tells them apart.
        assertThat(repository().findById(9L)).isNull();
    }

    @Test
    @DisplayName("a new log opens active, on the route it was created for")
    void aNewLogOpensActive() {
        when(nativeQueries.insertReturningKey(anyString(), any(Object[].class))).thenReturn(9L);

        assertThat(repository().createLog(6101, 7, "6101 01")).isEqualTo(9L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).insertReturningKey(sql.capture(), eq(6101), eq(7), eq("6101 01"));
        // statusid 2 is active; the date and time come from the server, not the
        // client, so two clubs in different zones agree on when it opened.
        assertThat(sql.getValue()).contains("statusid").contains("CURDATE(), CURTIME()");
    }

    @Test
    @DisplayName("linking a delivery to a log writes the log id onto its row")
    void linkingWritesTheLogId() {
        QuotationDelivery delivery = QuotationDelivery.builder().id(1L).build();
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(delivery));

        assertThat(repository().linkDeliveriesToLog(List.of(107L), 9L)).isEqualTo(1);

        assertThat(delivery.getLogCargueId()).isEqualTo(9.0);
        verify(deliveryRepository).save(delivery);
    }

    @Test
    @DisplayName("a quotation with no delivery is counted out, not failed on")
    void aQuotationWithoutADeliveryIsSkipped() {
        QuotationDelivery delivery = QuotationDelivery.builder().id(1L).build();
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findByQuotation_Id(108L)).thenReturn(Optional.empty());

        // The count is what the response reports, so it has to be the number
        // actually linked rather than the number asked for.
        assertThat(repository().linkDeliveriesToLog(List.of(107L, 108L), 9L)).isEqualTo(1);
        verify(deliveryRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("linking nothing links nothing, without touching the table")
    void linkingNothingTouchesNothing() {
        assertThat(repository().linkDeliveriesToLog(List.of(), 9L)).isZero();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("closing a log marks it sent and stamps both dates")
    void closingStampsBothDates() {
        when(nativeQueries.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(repository().closeLog(9L)).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).update(sql.capture(), eq(9L));
        assertThat(sql.getValue()).contains("statusid = 3")
                .contains("fechacierre = NOW()").contains("fechaenvio = NOW()");
    }

    @Test
    @DisplayName("closing a log that was already closed changes no row, and says so")
    void closingAnAlreadyClosedLogChangesNothing() {
        when(nativeQueries.update(anyString(), any(Object[].class))).thenReturn(0);

        // Which is how the service tells "closed just now" from "already closed".
        assertThat(repository().closeLog(9L)).isZero();
    }
}
