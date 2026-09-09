package com.dqs.api.service;

import com.dqs.api.dto.DeliveryLogResponse;
import com.dqs.api.repository.DeliveryLogRepository;
import com.dqs.api.util.MapUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryLogService {

    private final DeliveryLogRepository deliveryLogRepository;

    // ── Available deliveries filtered by route ────────────────────────────────

    public List<Map<String, Object>> getAvailableDeliveries(int storeId, String routeId) {
        log.info("[DeliveryLogService] getAvailableDeliveries storeId={} routeId={}", storeId, routeId);
        return deliveryLogRepository.findAvailableDeliveries(storeId, routeId);
    }

    // ── Create a new log and link quotations to it ────────────────────────────

    public DeliveryLogResponse createLog(int storeId, List<Long> quotationIds, int createdBy, String routeId) {
        log.info("[DeliveryLogService] createLog storeId={} quotations={} createdBy={} routeId={}", storeId, quotationIds, createdBy, routeId);
        long logId = deliveryLogRepository.createLog(storeId, createdBy, routeId);
        deliveryLogRepository.linkDeliveriesToLog(quotationIds, logId);
        Map<String, Object> log_ = deliveryLogRepository.findById(logId);
        List<Map<String, Object>> deliveries = deliveryLogRepository.findDeliveriesByLogId(logId);
        log.info("[DeliveryLogService] createLog OK logId={} linked={}", logId, deliveries.size());
        return toResponse(log_, deliveries);
    }

    // ── Add more quotations to an existing log ────────────────────────────────

    public DeliveryLogResponse addToLog(long logId, List<Long> quotationIds) {
        log.info("[DeliveryLogService] addToLog logId={} quotations={}", logId, quotationIds);
        deliveryLogRepository.linkDeliveriesToLog(quotationIds, logId);
        Map<String, Object> log_ = deliveryLogRepository.findById(logId);
        List<Map<String, Object>> deliveries = deliveryLogRepository.findDeliveriesByLogId(logId);
        return toResponse(log_, deliveries);
    }

    // ── Active logs filtered by route ─────────────────────────────────────────

    public List<DeliveryLogResponse> getActiveLogs(int storeId, String routeId) {
        log.info("[DeliveryLogService] getActiveLogs storeId={} routeId={}", storeId, routeId);
        return deliveryLogRepository.findByStoreAndStatus(storeId, 2, routeId).stream()
            .map(row -> toResponse(row, deliveryLogRepository.findDeliveriesByLogId(MapUtils.toLong(row.get("logcargueid")))))
            .toList();
    }

    // ── Close a log ───────────────────────────────────────────────────────────

    public boolean closeLog(long logId) {
        log.info("[DeliveryLogService] closeLog logId={}", logId);
        int rows = deliveryLogRepository.closeLog(logId);
        log.info("[DeliveryLogService] closeLog logId={} rows={}", logId, rows);
        return rows > 0;
    }

    // ── Historical logs filtered by route ─────────────────────────────────────

    public List<DeliveryLogResponse> getHistoricalLogs(int storeId, String routeId) {
        log.info("[DeliveryLogService] getHistoricalLogs storeId={} routeId={}", storeId, routeId);
        return deliveryLogRepository.findByStoreAndStatus(storeId, 3, routeId).stream()
            .map(row -> toResponse(row, deliveryLogRepository.findDeliveriesByLogId(MapUtils.toLong(row.get("logcargueid")))))
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeliveryLogResponse toResponse(Map<String, Object> row, List<Map<String, Object>> deliveries) {
        if (row == null) return null;
        return DeliveryLogResponse.builder()
            .logId(MapUtils.toLong(row.get("logcargueid")))
            .storeId(MapUtils.toInt(row.get("ps_tienda_id")))
            .statusId(MapUtils.toInt(row.get("statusid")))
            .createdBy(MapUtils.toInt(row.get("creado_por")))
            .date(MapUtils.str(row.get("fecha")))
            .closedAt(MapUtils.str(row.get("fechacierre")))
            .sentAt(MapUtils.str(row.get("fechaenvio")))
            .deliveryCount(deliveries.size())
            .deliveries(deliveries)
            .build();
    }

}
