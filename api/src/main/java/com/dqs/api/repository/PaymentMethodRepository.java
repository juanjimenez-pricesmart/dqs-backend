package com.dqs.api.repository;

import lombok.RequiredArgsConstructor;
import com.dqs.api.repository.support.NativeQueries;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PaymentMethodRepository {

    private final NativeQueries nativeQueries;

    public List<Map<String, Object>> findByCountry(String paisIso2) {
        return nativeQueries.list(
            "SELECT pago_id AS id, descripcion AS description, tender_key AS tenderKey " +
            "FROM orders_pago WHERE pais_iso2 = ? ORDER BY descripcion ASC",
            paisIso2);
    }
}
