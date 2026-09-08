package com.dqs.api.catalog.source;

import com.dqs.api.repository.support.NativeQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Catalogs read out of the legacy database, as they always have been.
 *
 * Active whenever {@code quotecenter.catalogs.own-tables} is not true, which is the
 * default. Native SQL because these tables belong to the application being
 * retired and must not be mapped as entities — see repository/CLAUDE.md.
 *
 * This class is the thing the migration is trying to delete. Nothing new should
 * be added to it; when the last environment is off the legacy tables it goes, with the
 * interface.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "quotecenter.catalogs.own-tables", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class LegacyCatalogSource implements CatalogSource {

    private final NativeQueries nativeQueries;

    @Override
    public String describe() {
        return "legacy MySQL (ps_rutas, ps_fel, orders_pago, ps_delivery_ciudades)";
    }

    /**
     * Legacy fetches the tariffs separately, one request per route selection
     * (orders/getrouteinfo → Model_orders::getrutaid). They come from the same
     * ps_rutas row the list already reads, so they ride along here: the panel is
     * informational and the extra round trip bought nothing.
     *
     * `14pallet_local` starts with a digit and has to be quoted.
     */
    @Override
    public List<RouteInfo> routesOfClub(Integer clubNumber) {
        return nativeQueries.list(
            "SELECT A.llave AS id, A.descripcion AS name, A.truck_size AS truckSize, " +
            "A.pallet_local AS palletRate, A.pallet_required AS palletMinimum, " +
            "A.halfpallet_local AS halfPalletRate, A.halfpallet_required AS halfPalletMinimum, " +
            "A.`14pallet_local` AS quarterPalletRate, A.`14pallet_usd` AS quarterPalletRateUsd, " +
            "TR.nombre AS routeTypeName, TR.codigo AS routeTypeCode " +
            "FROM ps_rutas A " +
            "LEFT JOIN ps_tipos_ruta TR ON A.tipo_ruta_id = TR.id AND TR.status = 'A' " +
            "WHERE A.ps_tienda_id = ?1 AND A.status = 'A' " +
            "ORDER BY A.llave",
            clubNumber).stream().map(LegacyCatalogSource::toRoute).toList();
    }

    /**
     * nombre_en is the type code — NIT, CUI, PHYSICAL, LEGAL, DIMEX, NITE —
     * which the per-type number validation keys off. Legacy carries it on the
     * option as data-type; without it the frontend can only match on the Spanish
     * label, which is a display string.
     */
    @Override
    public List<DocTypeInfo> documentTypesOfCountry(String countryIso2) {
        return nativeQueries.list(
            "SELECT felid, nombre_es AS descripcion, nombre_en AS typeCode, formato " +
            "FROM ps_fel WHERE pais_iso2 = ?1 ORDER BY nombre_es",
            countryIso2).stream().map(LegacyCatalogSource::toDocType).toList();
    }

    @Override
    public List<PaymentMethodInfo> paymentMethodsOfCountry(String countryIso2) {
        return nativeQueries.list(
            "SELECT pago_id AS id, descripcion AS description, tender_key AS tenderKey " +
            "FROM orders_pago WHERE pais_iso2 = ?1 ORDER BY descripcion ASC",
            countryIso2).stream().map(LegacyCatalogSource::toPaymentMethod).toList();
    }

    /**
     * Added despite the note above, because there was no alternative: the city
     * catalog existed nowhere else, and the flag has to keep working in both
     * positions. migration_delivery_cities.sql gives the own-tables side its
     * copy, so this method is retired with the rest of the class.
     *
     * `idco` is the id the delivery row stores (legacy `orders_delivery.ciudadid`),
     * cast to CHAR so it reaches the panel as the string its option list compares.
     * `status = 1` and the name ordering are legacy's own filter — Model_orders
     * ::getciudades.
     */
    @Override
    public List<DeliveryCityInfo> deliveryCitiesOfCountry(String countryIso2) {
        return nativeQueries.list(
            "SELECT CAST(idco AS CHAR) AS id, nombre AS name " +
            "FROM ps_delivery_ciudades " +
            "WHERE pais_iso2 = ?1 AND status = 1 " +
            "ORDER BY nombre ASC",
            countryIso2).stream().map(LegacyCatalogSource::toDeliveryCity).toList();
    }

    // ── row → record ─────────────────────────────────────────────────────────

    private static RouteInfo toRoute(Map<String, Object> r) {
        return new RouteInfo(
            str(r.get("id")), str(r.get("name")), dbl(r.get("truckSize")),
            dbl(r.get("palletRate")),      integer(r.get("palletMinimum")),
            dbl(r.get("halfPalletRate")),  integer(r.get("halfPalletMinimum")),
            dbl(r.get("quarterPalletRate")), dbl(r.get("quarterPalletRateUsd")),
            str(r.get("routeTypeName")), str(r.get("routeTypeCode")));
    }

    private static DocTypeInfo toDocType(Map<String, Object> r) {
        return new DocTypeInfo(integer(r.get("felid")), str(r.get("descripcion")),
                               str(r.get("typeCode")), str(r.get("formato")));
    }

    private static DeliveryCityInfo toDeliveryCity(Map<String, Object> r) {
        return new DeliveryCityInfo(str(r.get("id")), str(r.get("name")));
    }

    private static PaymentMethodInfo toPaymentMethod(Map<String, Object> r) {
        return new PaymentMethodInfo(integer(r.get("id")), str(r.get("description")),
                                     integer(r.get("tenderKey")));
    }

    private static String  str(Object o)     { return o == null ? null : o.toString(); }
    private static Double  dbl(Object o)     { return o instanceof Number n ? n.doubleValue() : null; }
    private static Integer integer(Object o) { return o instanceof Number n ? n.intValue()    : null; }
}
