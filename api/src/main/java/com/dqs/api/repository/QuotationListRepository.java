package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class QuotationListRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findSummaryByStoreId(int storeId) {
        return jdbcTemplate.queryForList("""
            SELECT q.id AS id,
                   qc.customer_name AS customerName,
                   q.date_time AS dateTime,
                   q.status_id AS statusId,
                   qp.quote_no AS quoteNo,
                   COALESCE(SUM(qi.amount), 0) AS amount
            FROM quotations q
            LEFT JOIN quotation_customers qc ON qc.quotation_id = q.id
            LEFT JOIN quotation_items qi ON qi.quotation_id = q.id
            LEFT JOIN quotation_payment qp ON qp.quotation_id = q.id
            WHERE q.store_id = ?
            GROUP BY q.id, qc.customer_name, q.date_time, q.status_id, qp.quote_no
            ORDER BY q.date_time DESC
            """, storeId);
    }
}
