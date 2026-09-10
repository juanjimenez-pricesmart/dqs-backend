package com.dqs.api.catalog.source;

import com.dqs.api.repository.support.NativeQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalogs as the legacy tables hold them.
 *
 * This is one half of a seam: the same four methods are served from our own
 * tables by OwnTablesCatalogSource, and the frontend must not be able to tell
 * which is switched on. So the shape of what comes out is the contract, and the
 * coercions are where it can quietly break — a rate that arrives as a
 * BigDecimal and leaves as a null is a route the delivery panel prices at zero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyCatalogSourceTest {

    @Mock private NativeQueries nativeQueries;

    private LegacyCatalogSource source() {
        return new LegacyCatalogSource(nativeQueries);
    }

    private String lastSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(nativeQueries).list(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private Map<String, Object> routeRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "6101 01");
        row.put("name", "Barranquilla Dry");
        row.put("truckSize", new BigDecimal("12.5"));
        row.put("palletRate", new BigDecimal("256.00"));
        row.put("palletMinimum", 11);
        row.put("halfPalletRate", new BigDecimal("43.00"));
        row.put("halfPalletMinimum", 6);
        row.put("quarterPalletRate", new BigDecimal("21.00"));
        row.put("quarterPalletRateUsd", new BigDecimal("5.00"));
        row.put("routeTypeName", "Intercity Delivery");
        row.put("routeTypeCode", "INTERCITY_DELIVERY");
        return row;
    }

    @Test
    @DisplayName("it says which tables it is reading, so the diagnostics can report it")
    void itNamesItsTables() {
        assertThat(source().describe()).contains("legacy MySQL").contains("ps_rutas");
    }

    @Test
    @DisplayName("a route comes back with all three tariff tiers and its type")
    void aRouteCarriesEveryTier() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(routeRow()));

        RouteInfo route = source().routesOfClub(6101).get(0);

        assertThat(route.id()).isEqualTo("6101 01");
        assertThat(route.name()).isEqualTo("Barranquilla Dry");
        assertThat(route.truckSize()).isEqualTo(12.5);
        assertThat(route.palletRate()).isEqualTo(256.0);
        assertThat(route.palletMinimum()).isEqualTo(11);
        assertThat(route.halfPalletRate()).isEqualTo(43.0);
        assertThat(route.halfPalletMinimum()).isEqualTo(6);
        assertThat(route.quarterPalletRate()).isEqualTo(21.0);
        assertThat(route.quarterPalletRateUsd()).isEqualTo(5.0);
        assertThat(route.routeTypeCode()).isEqualTo("INTERCITY_DELIVERY");
    }

    @Test
    @DisplayName("only active routes of that club are read, ordered by their key")
    void onlyActiveRoutesAreRead() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());

        source().routesOfClub(6101);

        String sql = lastSql();
        assertThat(sql).contains("A.status = 'A'").contains("A.ps_tienda_id = ?1")
                .contains("ORDER BY A.llave");
        // The route type join is optional AND filtered on active, so a route
        // whose type was retired still lists.
        assertThat(sql).contains("LEFT JOIN ps_tipos_ruta");
        verify(nativeQueries).list(contains("ps_rutas"), eq(6101));
    }

    @Test
    @DisplayName("a column that is not a number becomes nothing rather than a wrong figure")
    void nonNumericFiguresBecomeNull() {
        Map<String, Object> row = routeRow();
        row.put("truckSize", "N/A");
        row.put("palletRate", null);
        row.put("palletMinimum", "eleven");
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(row));

        RouteInfo route = source().routesOfClub(6101).get(0);

        // A null reaches the panel as "no tariff on record", which is visible;
        // a zero would read as a free route.
        assertThat(route.truckSize()).isNull();
        assertThat(route.palletRate()).isNull();
        assertThat(route.palletMinimum()).isNull();
    }

    @Test
    @DisplayName("a route with no type at all still comes back")
    void aRouteWithoutATypeStillComesBack() {
        Map<String, Object> row = routeRow();
        row.put("routeTypeName", null);
        row.put("routeTypeCode", null);
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(row));

        RouteInfo route = source().routesOfClub(6101).get(0);

        assertThat(route.routeTypeName()).isNull();
        assertThat(route.id()).isEqualTo("6101 01");
    }

    @Test
    @DisplayName("document types keep the legacy felid, because quotation_fiscal stores it")
    void documentTypesKeepTheLegacyId() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("felid", 1, "descripcion", "Factura electrónica",
                       "typeCode", "Electronic Invoice", "formato", "01")));

        DocTypeInfo type = source().documentTypesOfCountry("CO").get(0);

        assertThat(type.id()).isEqualTo(1);
        assertThat(type.description()).isEqualTo("Factura electrónica");
        assertThat(type.typeCode()).isEqualTo("Electronic Invoice");
        assertThat(type.format()).isEqualTo("01");
        verify(nativeQueries).list(contains("FROM ps_fel"), eq("CO"));
    }

    @Test
    @DisplayName("payment methods carry their tender key, which is what OMS is sent")
    void paymentMethodsCarryTheirTenderKey() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 5, "description", "Transferencia", "tenderKey", 110)));

        PaymentMethodInfo method = source().paymentMethodsOfCountry("CO").get(0);

        assertThat(method.id()).isEqualTo(5);
        assertThat(method.description()).isEqualTo("Transferencia");
        assertThat(method.tenderKey()).isEqualTo(110);
        verify(nativeQueries).list(contains("FROM orders_pago"), eq("CO"));
    }

    @Test
    @DisplayName("delivery cities come back with their name trimmed, only the active ones")
    void deliveryCitiesAreTrimmedAndActive() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", "1", "name", "BARRANQUILLA")));

        DeliveryCityInfo city = source().deliveryCitiesOfCountry("CO").get(0);

        assertThat(city.id()).isEqualTo("1");
        assertThat(city.name()).isEqualTo("BARRANQUILLA");
        String sql = lastSql();
        // The id is cast and the name trimmed in SQL, because that column is
        // CHAR-padded in the legacy table.
        assertThat(sql).contains("CAST(idco AS CHAR)").contains("TRIM(nombre)")
                .contains("status = 1");
    }

    @Test
    @DisplayName("an empty catalog is an empty list from every one of the four")
    void everyCatalogCanBeEmpty() {
        when(nativeQueries.list(anyString(), any(Object[].class))).thenReturn(List.of());
        LegacyCatalogSource source = source();

        assertThat(source.routesOfClub(6101)).isEmpty();
        assertThat(source.documentTypesOfCountry("ZZ")).isEmpty();
        assertThat(source.paymentMethodsOfCountry("ZZ")).isEmpty();
        assertThat(source.deliveryCitiesOfCountry("ZZ")).isEmpty();
    }
}
