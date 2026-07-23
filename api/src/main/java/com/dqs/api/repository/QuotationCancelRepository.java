package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class QuotationCancelRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findReasons() {
        return jdbcTemplate.queryForList("SELECT id, description FROM quotation_cancel_reasons ORDER BY id");
    }

    public void cancel(Long quotationId, int reasonId) {
        jdbcTemplate.update(
            "UPDATE quotations SET status_id = 4, cancel_reason_id = ? WHERE id = ?",
            reasonId, quotationId);
    }
}
