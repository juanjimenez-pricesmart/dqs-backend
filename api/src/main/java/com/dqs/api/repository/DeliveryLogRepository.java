package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import com.dqs.api.repository.support.NativeQueries;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DeliveryLogRepository {

    private final NativeQueries nativeQueries;
    private final QuotationDeliveryRepository deliveryRepository;

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> findAvailableDeliveries(int storeId, String routeId) {
        return nativeQueries.list("""
            SELECT qd.id, qd.quotation_id AS quotationId, qd.amount, qd.address,
                   qd.delivery_date AS deliveryDate, qd.hour_from AS hourFrom,
                   qd.hour_to AS hourTo, qd.route_id AS routeId, qd.pallets,
                   qc.customer_name AS customerName, qc.customer_membership AS customerMembership
            FROM quotation_delivery qd
            JOIN quotations q ON q.id = qd.quotation_id
            LEFT JOIN quotation_customers qc ON qc.quotation_id = qd.quotation_id
            WHERE q.store_id = ? AND qd.logcargueid IS NULL AND qd.route_id = ?
            ORDER BY qd.delivery_date, qd.hour_from
            """, storeId, routeId);
    }

    public List<Map<String, Object>> findByStoreAndStatus(int storeId, int statusId, String routeId) {
        return nativeQueries.list("""
            SELECT l.logcargueid, l.ps_tienda_id, l.statusid, l.creado_por,
                   l.fecha, l.hora, l.fechacierre, l.fechaenvio, l.delivery_ruta,
                   COUNT(qd.id) AS delivery_count
            FROM ps_delivery_log_cargue l
            LEFT JOIN quotation_delivery qd ON qd.logcargueid = l.logcargueid
            WHERE l.ps_tienda_id = ? AND l.statusid = ? AND l.delivery_ruta = ?
            GROUP BY l.logcargueid
            ORDER BY l.fecha DESC, l.hora DESC
            """, storeId, statusId, routeId);
    }

    public List<Map<String, Object>> findDeliveriesByLogId(long logId) {
        return nativeQueries.list("""
            SELECT qd.id, qd.quotation_id AS quotationId, qd.amount, qd.address,
                   qd.delivery_date AS deliveryDate, qd.hour_from AS hourFrom,
                   qd.hour_to AS hourTo, qd.route_id AS routeId, qd.pallets,
                   qc.customer_name AS customerName, qc.customer_membership AS customerMembership
            FROM quotation_delivery qd
            LEFT JOIN quotation_customers qc ON qc.quotation_id = qd.quotation_id
            WHERE qd.logcargueid = ?
            ORDER BY qd.delivery_date, qd.hour_from
            """, logId);
    }

    public Map<String, Object> findById(long logId) {
        List<Map<String, Object>> rows = nativeQueries.list(
            "SELECT * FROM ps_delivery_log_cargue WHERE logcargueid = ?", logId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Must stay transactional: the generated key is read on the same connection. */
    @Transactional
    public long createLog(int storeId, int createdBy, String routeId) {
        return nativeQueries.insertReturningKey(
            "INSERT INTO ps_delivery_log_cargue " +
            "  (fecha, hora, ps_tienda_id, empresaid, creado_por, statusid, quote_type, delivery_ruta) " +
            "VALUES (CURDATE(), CURTIME(), ?, 1, ?, 2, 1, ?)",
            storeId, createdBy, routeId);
    }

    /**
     * `quotation_delivery` is ours and mapped, so this goes through the entity
     * rather than a native UPDATE — a raw statement would leave any instance
     * already loaded in the persistence context holding a stale logcargueid.
     */
    @Transactional
    public int linkDeliveriesToLog(List<Long> quotationIds, long logId) {
        int updated = 0;
        for (Long quotationId : quotationIds) {
            var delivery = deliveryRepository.findByQuotation_Id(quotationId).orElse(null);
            if (delivery == null) continue;
            delivery.setLoadLogId((double) logId);
            deliveryRepository.save(delivery);
            updated++;
        }
        return updated;
    }

    public int closeLog(long logId) {
        return nativeQueries.update(
            "UPDATE ps_delivery_log_cargue SET statusid = 3, fechacierre = NOW(), fechaenvio = NOW() " +
            "WHERE logcargueid = ?", logId);
    }
}
