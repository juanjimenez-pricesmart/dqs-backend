package com.dqs.api.service;

import com.dqs.api.dto.DeliveryLogResponse;
import com.dqs.api.repository.DeliveryLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A load groups the deliveries that go out on one truck, so the two things that
 * matter are that quotations get linked to the log they were added to, and that
 * a row's values survive the trip out of native SQL — where the same column can
 * come back as a Long, an Integer or a BigDecimal depending on the driver.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryLogServiceTest {

    private static final int STORE = 6101;
    private static final String ROUTE = "6101 01";

    @Mock private DeliveryLogRepository deliveryLogRepository;
    @InjectMocks private DeliveryLogService service;

    private Map<String, Object> logRow(Object logId) {
        Map<String, Object> row = new HashMap<>();
        row.put("logcargueid", logId);
        row.put("ps_tienda_id", STORE);
        row.put("statusid", 2);
        row.put("creado_por", 1);
        row.put("fecha", "2026-09-10");
        row.put("fechacierre", null);
        row.put("fechaenvio", null);
        return row;
    }

    @Test
    @DisplayName("available deliveries are asked for by store and route")
    void availableDeliveriesAreFilteredByRoute() {
        when(deliveryLogRepository.findAvailableDeliveries(STORE, ROUTE))
            .thenReturn(List.of(Map.of("quotationId", 107L)));

        assertThat(service.getAvailableDeliveries(STORE, ROUTE)).hasSize(1);
    }

    @Test
    @DisplayName("creating a log links the chosen quotations to it")
    void createLogLinksTheQuotations() {
        when(deliveryLogRepository.createLog(STORE, 1, ROUTE)).thenReturn(9L);
        when(deliveryLogRepository.findById(9L)).thenReturn(logRow(9L));
        when(deliveryLogRepository.findDeliveriesByLogId(9L))
            .thenReturn(List.of(Map.of("quotationId", 107L), Map.of("quotationId", 108L)));

        DeliveryLogResponse out = service.createLog(STORE, List.of(107L, 108L), 1, ROUTE);

        verify(deliveryLogRepository).linkDeliveriesToLog(List.of(107L, 108L), 9L);
        assertThat(out.getLogId()).isEqualTo(9L);
        assertThat(out.getDeliveryCount()).isEqualTo(2);
        assertThat(out.getStoreId()).isEqualTo(STORE);
        assertThat(out.getFecha()).isEqualTo("2026-09-10");
    }

    @Test
    @DisplayName("adding to a log links the new quotations and returns the whole load")
    void addToLogReturnsTheFullLoad() {
        when(deliveryLogRepository.findById(9L)).thenReturn(logRow(9L));
        when(deliveryLogRepository.findDeliveriesByLogId(9L))
            .thenReturn(List.of(Map.of("quotationId", 107L), Map.of("quotationId", 109L)));

        DeliveryLogResponse out = service.addToLog(9L, List.of(109L));

        verify(deliveryLogRepository).linkDeliveriesToLog(List.of(109L), 9L);
        assertThat(out.getDeliveryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a log that vanished between the write and the read maps to null")
    void missingLogRowMapsToNull() {
        when(deliveryLogRepository.findById(9L)).thenReturn(null);
        when(deliveryLogRepository.findDeliveriesByLogId(9L)).thenReturn(List.of());

        assertThat(service.addToLog(9L, List.of(109L))).isNull();
    }

    @Test
    @DisplayName("active logs are status 2, historical ones status 3")
    void activeAndHistoricalUseDifferentStatuses() {
        when(deliveryLogRepository.findByStoreAndStatus(STORE, 2, ROUTE)).thenReturn(List.of(logRow(9L)));
        when(deliveryLogRepository.findByStoreAndStatus(STORE, 3, ROUTE)).thenReturn(List.of(logRow(8L)));
        when(deliveryLogRepository.findDeliveriesByLogId(anyLong())).thenReturn(List.of());

        assertThat(service.getActiveLogs(STORE, ROUTE)).extracting(DeliveryLogResponse::getLogId)
            .containsExactly(9L);
        assertThat(service.getHistoricalLogs(STORE, ROUTE)).extracting(DeliveryLogResponse::getLogId)
            .containsExactly(8L);
    }

    @Test
    @DisplayName("closing reports whether a row actually changed")
    void closeReportsWhetherItChangedAnything() {
        when(deliveryLogRepository.closeLog(9L)).thenReturn(1);
        when(deliveryLogRepository.closeLog(99L)).thenReturn(0);

        assertThat(service.closeLog(9L)).isTrue();
        // A log already closed, or one that never existed
        assertThat(service.closeLog(99L)).isFalse();
    }

    /*
     * The rows come out of native SQL, where the driver decides the numeric
     * type. Each coercion is exercised with the shapes it can actually see.
     */
    @Test
    @DisplayName("ids and counts survive arriving as text")
    void numbersArrivingAsTextAreRead() {
        Map<String, Object> row = logRow("9");
        row.put("ps_tienda_id", "6101");
        row.put("statusid", "2");
        row.put("creado_por", "1");
        when(deliveryLogRepository.findById(9L)).thenReturn(row);
        when(deliveryLogRepository.findDeliveriesByLogId(9L)).thenReturn(List.of());

        DeliveryLogResponse out = service.addToLog(9L, List.of());

        assertThat(out.getLogId()).isEqualTo(9L);
        assertThat(out.getStoreId()).isEqualTo(6101);
        assertThat(out.getStatusId()).isEqualTo(2);
        assertThat(out.getCreatedBy()).isEqualTo(1);
    }

    @Test
    @DisplayName("null columns come through as null rather than as the text \"null\"")
    void nullColumnsStayNull() {
        Map<String, Object> row = logRow(9L);
        row.put("ps_tienda_id", null);
        row.put("statusid", null);
        row.put("creado_por", null);
        row.put("fecha", null);
        when(deliveryLogRepository.findById(9L)).thenReturn(row);
        when(deliveryLogRepository.findDeliveriesByLogId(9L)).thenReturn(List.of());

        DeliveryLogResponse out = service.addToLog(9L, List.of());

        assertThat(out.getStoreId()).isNull();
        assertThat(out.getStatusId()).isNull();
        assertThat(out.getCreatedBy()).isNull();
        assertThat(out.getFecha()).isNull();
    }

    @Test
    @DisplayName("a log id arriving as null does not break the mapping")
    void nullLogIdIsTolerated() {
        when(deliveryLogRepository.findById(9L)).thenReturn(logRow(null));
        when(deliveryLogRepository.findDeliveriesByLogId(9L)).thenReturn(List.of());

        assertThat(service.addToLog(9L, List.of()).getLogId()).isNull();
    }

    @Test
    @DisplayName("no logs for a route is an empty list, not a failure")
    void noLogsIsEmpty() {
        when(deliveryLogRepository.findByStoreAndStatus(anyInt(), anyInt(), anyString()))
            .thenReturn(List.of());

        assertThat(service.getActiveLogs(STORE, ROUTE)).isEmpty();
    }
}
