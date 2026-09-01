package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class QuotationListRepository {

    private static final int STATUS_PENDING = 1;

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findByStoreFiltered(int storeId, int statusId,
                                                         boolean mineOnly, Integer userId,
                                                         Integer periodId) {
        StringBuilder sql = new StringBuilder("""
            SELECT q.id AS id,
                   q.status_id AS statusId,
                   qp.quote_no AS quoteNo,
                   qc.customer_membership AS customerMembership,
                   qc.customer_name AS customerName,
                   qc.customer_business AS customerBusiness,
                   COALESCE(qt.net_amount,
                            (SELECT COALESCE(SUM(qi.amount), 0)
                               FROM quotation_items qi
                              WHERE qi.quotation_id = q.id)) AS amount,
                   qt.delivery_amount AS deliveryAmount,
                   (SELECT COUNT(*)
                      FROM quotation_items qi
                     WHERE qi.quotation_id = q.id) AS itemCount,
                   qp.quote_type_id AS quoteType,
                   q.date_time AS dateTime,
                   u.username AS userName,
                   EXISTS(SELECT 1
                            FROM quotation_items qi
                           WHERE qi.quotation_id = q.id
                             AND qi.variacion = 1) AS hasPriceVariation
            FROM quotations q
            LEFT JOIN quotation_customers qc ON qc.quotation_id = q.id
            LEFT JOIN quotation_totals qt ON qt.quotation_id = q.id
            LEFT JOIN quotation_payment qp ON qp.quotation_id = q.id
            LEFT JOIN users u ON u.id = q.user_id
            WHERE q.store_id = ?
              AND q.status_id = ?
            """);

        List<Object> params = new ArrayList<>();
        params.add(storeId);
        params.add(statusId);

        if (statusId != STATUS_PENDING) {
            int[] monthYear = resolvePeriod(periodId);
            sql.append(" AND MONTH(q.date_time) = ? AND YEAR(q.date_time) = ?");
            params.add(monthYear[0]);
            params.add(monthYear[1]);
        }

        if (mineOnly && userId != null) {
            sql.append(" AND q.user_id = ?");
            params.add(userId);
        }

        sql.append(" ORDER BY q.id DESC");

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> findDeliveriesByStore(int storeId) {
        return jdbcTemplate.queryForList("""
            SELECT CONCAT(LPAD(qd.hour_from, 2, '0'), ':00-', LPAD(qd.hour_to, 2, '0'), ':00') AS hour,
                   qd.delivery_date AS deliveryDate,
                   q.id AS quotationId,
                   qc.customer_name AS memberName,
                   qd.address AS address,
                   qd.amount AS deliveryAmount,
                   q.date_time AS createdAt,
                   u.username AS userName
            FROM quotation_delivery qd
            JOIN quotations q ON q.id = qd.quotation_id
            LEFT JOIN quotation_customers qc ON qc.quotation_id = q.id
            LEFT JOIN users u ON u.id = q.user_id
            WHERE q.store_id = ?
              AND q.status_id = 3
              AND qd.amount > 0
            ORDER BY qd.id
            """, storeId);
    }

    public List<Map<String, Object>> findSummaryByStatus(int storeId, Integer periodId) {
        int[] monthYear = resolvePeriod(periodId);
        return jdbcTemplate.queryForList("""
            SELECT q.status_id AS statusId,
                   COUNT(q.id) AS count,
                   ROUND(COALESCE(SUM(qt.net_amount), 0), 2) AS totalAmount
            FROM quotations q
            LEFT JOIN quotation_totals qt ON qt.quotation_id = q.id
            WHERE q.store_id = ?
              AND q.status_id <> 1
              AND MONTH(q.date_time) = ?
              AND YEAR(q.date_time) = ?
            GROUP BY q.status_id
            UNION
            SELECT q.status_id AS statusId,
                   COUNT(q.id) AS count,
                   ROUND(COALESCE(SUM(qt.net_amount), 0), 2) AS totalAmount
            FROM quotations q
            LEFT JOIN quotation_totals qt ON qt.quotation_id = q.id
            WHERE q.store_id = ?
              AND q.status_id = 1
            GROUP BY q.status_id
            """, storeId, monthYear[0], monthYear[1], storeId);
    }

    public List<Map<String, Object>> findPeriods() {
        return jdbcTemplate.queryForList("""
            SELECT c.ps_cierre_mensual_id AS id,
                   c.ps_cierre_mensual_mes AS month,
                   c.ps_cierre_mensual_anio AS year,
                   CONCAT(m.ps_mes_descripcion, '-', c.ps_cierre_mensual_anio) AS label,
                   CONCAT(m.ps_mes_descripcion_eng, '-', c.ps_cierre_mensual_anio) AS labelEn
            FROM ps_cierre_mensual c
            JOIN ps_mes m ON m.ps_mes_id = c.ps_cierre_mensual_mes
            ORDER BY c.ps_cierre_mensual_id DESC
            """);
    }

    private int[] resolvePeriod(Integer periodId) {
        if (periodId == null) {
            return new int[]{0, 0};
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ps_cierre_mensual_mes, ps_cierre_mensual_anio FROM ps_cierre_mensual WHERE ps_cierre_mensual_id = ?",
                periodId);
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        Map<String, Object> row = rows.get(0);
        return new int[]{
                ((Number) row.get("ps_cierre_mensual_mes")).intValue(),
                ((Number) row.get("ps_cierre_mensual_anio")).intValue()
        };
    }
}
