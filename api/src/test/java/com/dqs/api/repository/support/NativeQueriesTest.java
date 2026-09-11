package com.dqs.api.repository.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native-SQL seam.
 *
 * Every legacy `ps_*` table is read through this class rather than mapped as an
 * entity, so its two jobs are worth pinning: bind parameters where JPA expects
 * them, and hand rows back in the shape the controllers already return.
 *
 * The binding is the part that bites. JPA positional parameters are 1-based and
 * the old JdbcTemplate array was 0-based, so an off-by-one here does not fail —
 * it silently reads the wrong club, or the wrong quotation.
 *
 * What these tests cannot say is whether the SQL is valid MySQL. That needs a
 * real server with the legacy schema, which is a decision of its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NativeQueriesTest {

    @Mock private EntityManager em;
    @Mock private Query query;

    private NativeQueries queries() {
        NativeQueries q = new NativeQueries();
        try {
            Field f = NativeQueries.class.getDeclaredField("em");
            f.setAccessible(true);
            f.set(q, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return q;
    }

    /** A Tuple with the given aliases and values, as the driver returns one. */
    private Tuple tuple(List<String> aliases, List<Object> values) {
        Tuple t = org.mockito.Mockito.mock(Tuple.class);
        List<TupleElement<?>> elements = new ArrayList<>();
        for (String alias : aliases) {
            TupleElement<?> element = org.mockito.Mockito.mock(TupleElement.class);
            when(element.getAlias()).thenReturn(alias);
            elements.add(element);
        }
        when(t.getElements()).thenReturn(elements);
        for (int i = 0; i < values.size(); i++) {
            when(t.get(i)).thenReturn(values.get(i));
        }
        return t;
    }

    private void stubTupleQuery(List<Tuple> rows) {
        when(em.createNativeQuery(anyString(), eq(Tuple.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    private void stubPlainQuery(List<?> results) {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(new ArrayList<>(results));
    }

    // ── list ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rows come back keyed by the column label, in the order the SELECT spells them")
    void listKeepsSelectOrder() {
        stubTupleQuery(List.of(tuple(
                List.of("id", "nombre", "moneda"),
                Arrays.asList(6101, "Barranquilla", "COP"))));

        List<Map<String, Object>> rows = queries().list("SELECT id, nombre, moneda FROM ps_tienda");

        assertThat(rows).hasSize(1);
        // Insertion-ordered, because the frontend reads these as objects and
        // the PDF templates walk them in order.
        assertThat(rows.get(0).keySet()).containsExactly("id", "nombre", "moneda");
        assertThat(rows.get(0)).containsEntry("nombre", "Barranquilla");
    }

    @Test
    @DisplayName("parameters bind from one, not from zero")
    void parametersBindFromOne() {
        stubTupleQuery(List.of());

        queries().list("SELECT * FROM ps_tienda WHERE ps_tienda_id = ? AND pais_iso2 = ?", 6101, "CO");

        // An off-by-one here reads the wrong club rather than failing.
        verify(query).setParameter(1, 6101);
        verify(query).setParameter(2, "CO");
    }

    @Test
    @DisplayName("a query with no parameters binds none")
    void noParametersBindNone() {
        stubTupleQuery(List.of());

        queries().list("SHOW TABLES LIKE 'ps_%'");

        verify(query, org.mockito.Mockito.never()).setParameter(anyInt(), any());
    }

    @Test
    @DisplayName("a column the SELECT did not name is keyed by its position")
    void unnamedColumnsFallBackToTheirIndex() {
        Tuple t = org.mockito.Mockito.mock(Tuple.class);
        TupleElement<?> unnamed = org.mockito.Mockito.mock(TupleElement.class);
        when(unnamed.getAlias()).thenReturn(null);
        when(t.getElements()).thenReturn(List.of(unnamed));
        when(t.get(0)).thenReturn(42);
        stubTupleQuery(List.of(t));

        // Better a numeric key than a map with a null one, which no caller can read.
        assertThat(queries().list("SELECT COUNT(*) FROM quotations").get(0))
                .containsEntry("0", 42);
    }

    @Test
    @DisplayName("a query that matched nothing is an empty list, not a null")
    void noRowsIsAnEmptyList() {
        stubTupleQuery(List.of());

        assertThat(queries().list("SELECT 1 FROM quotations WHERE 1 = 0")).isEmpty();
    }

    @Test
    @DisplayName("a NULL column keeps its key, with no value")
    void nullColumnsKeepTheirKey() {
        stubTupleQuery(List.of(tuple(List.of("quoteNo"), Arrays.asList((Object) null))));

        Map<String, Object> row = queries().list("SELECT quote_no AS quoteNo FROM quotation_payment").get(0);

        // The key has to exist: the frontend distinguishes an absent field from
        // a null one.
        assertThat(row).containsKey("quoteNo").containsEntry("quoteNo", null);
    }

    // ── first ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the first row asks the database for one, rather than reading them all")
    void firstLimitsToOneRow() {
        stubTupleQuery(List.of(tuple(List.of("id"), List.of(107))));

        assertThat(queries().first("SELECT id FROM quotations WHERE store_id = ?", 6101))
                .isPresent()
                .get().extracting(m -> m.get("id")).isEqualTo(107);
        verify(query).setMaxResults(1);
    }

    @Test
    @DisplayName("no first row is an empty optional, not a null map")
    void firstIsEmptyWhenNothingMatched() {
        stubTupleQuery(List.of());

        assertThat(queries().first("SELECT id FROM quotations WHERE id = ?", 404L)).isEmpty();
    }

    // ── scalar ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a scalar comes back as the type asked for, whatever the driver handed over")
    void scalarConvertsToTheRequestedType() {
        NativeQueries queries = queries();

        stubPlainQuery(List.of(BigDecimal.valueOf(42)));
        assertThat(queries.scalar("SELECT COUNT(*) FROM quotations", Integer.class)).contains(42);

        stubPlainQuery(List.of(42));
        assertThat(queries.scalar("SELECT COUNT(*) FROM quotations", Long.class)).contains(42L);

        stubPlainQuery(List.of(42));
        assertThat(queries.scalar("SELECT COUNT(*) FROM quotations", Double.class)).contains(42.0);

        stubPlainQuery(List.of(42));
        assertThat(queries.scalar("SELECT COUNT(*) FROM quotations", String.class)).contains("42");
    }

    @Test
    @DisplayName("a value already of the right type is passed straight through")
    void scalarPassesTheRightTypeThrough() {
        stubPlainQuery(List.of("CO"));

        assertThat(queries().scalar("SELECT pais_iso2 FROM ps_tienda", String.class)).contains("CO");
    }

    @Test
    @DisplayName("no row and a NULL value are both empty — the caller cannot tell them apart, and does not need to")
    void scalarIsEmptyForNoRowAndForNull() {
        NativeQueries queries = queries();

        stubPlainQuery(List.of());
        assertThat(queries.scalar("SELECT MAX(id) FROM quotations", Long.class)).isEmpty();

        stubPlainQuery(Arrays.asList((Object) null));
        assertThat(queries.scalar("SELECT MAX(id) FROM quotations", Long.class)).isEmpty();
    }

    @Test
    @DisplayName("a value that cannot become the requested type says so, rather than coercing nonsense")
    void scalarRefusesAnImpossibleConversion() {
        stubPlainQuery(List.of(new java.util.Date()));

        assertThatThrownBy(() -> queries().scalar("SELECT date_time FROM quotations", Integer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot read a Date as Integer");
    }

    // ── column ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a single column comes back as a list, converted row by row")
    void columnConvertsEveryRow() {
        stubPlainQuery(List.of(BigDecimal.ONE, BigDecimal.valueOf(2)));

        assertThat(queries().column("SELECT id FROM quotations", Long.class)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a NULL in the column stays a null rather than becoming the text \"null\"")
    void columnKeepsNulls() {
        stubPlainQuery(Arrays.asList("ps_tienda", null));

        assertThat(queries().column("SHOW TABLES LIKE 'ps_%'", String.class))
                .containsExactly("ps_tienda", null);
    }

    // ── update and insert ─────────────────────────────────────────────────

    @Test
    @DisplayName("an update reports how many rows it changed")
    void updateReportsAffectedRows() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        assertThat(queries().update("UPDATE ps_delivery_log_cargue SET statusid = 3 WHERE logcargueid = ?", 9L))
                .isEqualTo(1);
        verify(query).setParameter(1, 9L);
    }

    @Test
    @DisplayName("an insert hands back the key MySQL generated for it")
    void insertReturnsItsGeneratedKey() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        // LAST_INSERT_ID() is scoped to the connection, and both statements run
        // on the same one because the caller is inside a transaction.
        when(query.getResultList()).thenReturn(new ArrayList<>(List.of(BigDecimal.valueOf(9))));

        assertThat(queries().insertReturningKey("INSERT INTO ps_delivery_log_cargue (ps_tienda_id) VALUES (?)", 6101))
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("an insert that generated no key is an error, not a log with id zero")
    void insertWithoutAKeyFails() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        when(query.getResultList()).thenReturn(new ArrayList<>());

        // Returning 0 would attach every delivery to a log that does not exist.
        assertThatThrownBy(() -> queries().insertReturningKey("INSERT INTO ps_delivery_log_cargue VALUES ()"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSERT generated no key");
    }
}
