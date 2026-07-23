package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DeliveryLogRepository {

    private final JdbcTemplate jdbcTemplate;

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> findAvailableDeliveries(int storeId, String routeId) {
        return jdbcTemplate.queryForList("""
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
        return jdbcTemplate.queryForList("""
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
        return jdbcTemplate.queryForList("""
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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ps_delivery_log_cargue WHERE logcargueid = ?", logId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public long createLog(int storeId, int createdBy, String routeId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO ps_delivery_log_cargue " +
                "  (fecha, hora, ps_tienda_id, empresaid, creado_por, statusid, quote_type, delivery_ruta) " +
                "VALUES (CURDATE(), CURTIME(), ?, 1, ?, 2, 1, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, storeId);
            ps.setInt(2, createdBy);
            ps.setString(3, routeId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public int linkDeliveriesToLog(List<Long> quotationIds, long logId) {
        int updated = 0;
        for (Long quotationId : quotationIds) {
            updated += jdbcTemplate.update(
                "UPDATE quotation_delivery SET logcargueid = ? WHERE quotation_id = ?",
                logId, quotationId);
        }
        return updated;
    }

    public int closeLog(long logId) {
        return jdbcTemplate.update(
            "UPDATE ps_delivery_log_cargue SET statusid = 3, fechacierre = NOW(), fechaenvio = NOW() " +
            "WHERE logcargueid = ?", logId);
    }
}
