package com.dqs.api.repository.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Native SQL through JPA, for tables that must not be mapped as entities.
 *
 * The legacy tables — `ps_rutas`, `ps_fel`, `ps_socios*`, `ps_tienda`,
 * `orders_pago` and the rest — belong to the application being retired. We do
 * not own their schema and cannot stop it changing. Declaring an @Entity over
 * one of them would put it under `ddl-auto=validate`, which turns a column
 * rename on their side into a backend that will not start at all: quotations,
 * invoicing and everything else down, over a table only one endpoint reads.
 * Reading them through native SQL keeps that blast radius at one query.
 *
 * What this class exists to avoid is the other half of the problem — that
 * "no entity" used to mean "a second persistence API". Everything now goes
 * through the EntityManager and shares its transaction; JdbcTemplate is gone.
 *
 * Results come back as insertion-ordered maps keyed by the column label exactly
 * as the SELECT spells it, which is the shape the controllers already return
 * and the frontend already reads.
 */
@Component
public class NativeQueries {

    @PersistenceContext
    private EntityManager em;

    /** Rows as maps of column label to value, in SELECT order. */
    public List<Map<String, Object>> list(String sql, Object... params) {
        Query query = em.createNativeQuery(sql, Tuple.class);
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Tuple> tuples = query.getResultList();
        List<Map<String, Object>> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) rows.add(toMap(t));
        return rows;
    }

    /** The first row, or empty when the query matched nothing. */
    public Optional<Map<String, Object>> first(String sql, Object... params) {
        Query query = em.createNativeQuery(sql, Tuple.class);
        bind(query, params);
        query.setMaxResults(1);
        @SuppressWarnings("unchecked")
        List<Tuple> tuples = query.getResultList();
        return tuples.isEmpty() ? Optional.empty() : Optional.of(toMap(tuples.get(0)));
    }

    /**
     * A single value from the first row — a COUNT, a MAX, one column.
     * Empty when the query matched no row, or when the value itself is NULL.
     */
    public <T> Optional<T> scalar(String sql, Class<T> type, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        query.setMaxResults(1);
        List<?> results = query.getResultList();
        if (results.isEmpty() || results.get(0) == null) return Optional.empty();
        return Optional.of(convert(results.get(0), type));
    }

    /** A single column, as a list — one entry per row. */
    public <T> List<T> column(String sql, Class<T> type, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        List<T> values = new ArrayList<>();
        for (Object raw : query.getResultList()) {
            values.add(raw == null ? null : convert(raw, type));
        }
        return values;
    }

    /**
     * An INSERT, returning the auto-increment key it generated.
     *
     * JPA has no equivalent of JDBC's RETURN_GENERATED_KEYS, so this reads
     * LAST_INSERT_ID() straight after. That value is scoped to the database
     * connection, and both statements run on the same one as long as the caller
     * is inside a transaction — which it must be. A concurrent insert from
     * another connection cannot be picked up here.
     */
    public long insertReturningKey(String sql, Object... params) {
        update(sql, params);
        return scalar("SELECT LAST_INSERT_ID()", Long.class)
            .orElseThrow(() -> new IllegalStateException("INSERT generated no key: " + sql));
    }

    /** INSERT, UPDATE or DELETE. Returns the number of affected rows. */
    public int update(String sql, Object... params) {
        Query query = em.createNativeQuery(sql);
        bind(query, params);
        return query.executeUpdate();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** JPA positional parameters are 1-based, unlike the JDBC template's array. */
    private void bind(Query query, Object... params) {
        for (int i = 0; i < params.length; i++) query.setParameter(i + 1, params[i]);
    }

    private Map<String, Object> toMap(Tuple tuple) {
        List<TupleElement<?>> elements = tuple.getElements();
        Map<String, Object> row = new LinkedHashMap<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            String label = elements.get(i).getAlias();
            row.put(label != null ? label : String.valueOf(i), tuple.get(i));
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private <T> T convert(Object value, Class<T> type) {
        if (type.isInstance(value)) return (T) value;
        if (value instanceof Number n) {
            if (type == Integer.class) return (T) Integer.valueOf(n.intValue());
            if (type == Long.class)    return (T) Long.valueOf(n.longValue());
            if (type == Double.class)  return (T) Double.valueOf(n.doubleValue());
        }
        if (type == String.class) return (T) value.toString();
        throw new IllegalArgumentException(
            "Cannot read a " + value.getClass().getSimpleName() + " as " + type.getSimpleName());
    }
}
