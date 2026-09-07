package com.dqs.api.catalog;

import com.dqs.api.catalog.model.Route;
import com.dqs.api.catalog.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Is the Azure catalog database reachable, and does it hold what we expect?
 *
 * Exists because the catalogs have no consumer yet: the entities and
 * repositories are in place but no service reads them, so nothing would notice
 * a broken connection or a mapping that drifted from the schema until the day
 * something is switched over. This gives the switch-over a way to confirm the
 * connection from inside the application, against the real instance, before any
 * user-facing code depends on it.
 *
 * Present only when azure.datasource.enabled is true — with the flag off the
 * repositories it needs do not exist as beans, so it must not either.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diag/catalog")
@ConditionalOnProperty(name = "azure.datasource.enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Diagnóstico", description = "Estado de la base de catálogos en Azure")
public class CatalogDiagController {

    private final CountryRepository countries;
    private final ClubRepository clubs;
    private final RouteTypeRepository routeTypes;
    private final RouteRepository routes;
    private final RoutePriceRepository routePrices;
    private final FiscalDocumentTypeRepository docTypes;
    private final PaymentMethodTypeRepository methodTypes;
    private final CountryPaymentMethodRepository countryMethods;
    private final ExchangeRateRepository rates;

    @Operation(summary = "Conteos por tabla de catálogo")
    @GetMapping
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public ResponseEntity<Map<String, Object>> counts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("countries",               countries.count());
        m.put("clubs",                   clubs.count());
        m.put("route_types",             routeTypes.count());
        m.put("routes",                  routes.count());
        m.put("route_prices",            routePrices.count());
        m.put("fiscal_document_types",   docTypes.count());
        m.put("payment_method_types",    methodTypes.count());
        m.put("country_payment_methods", countryMethods.count());
        m.put("exchange_rates",          rates.count());
        return ResponseEntity.ok(m);
    }

    /**
     * A club's routes with their tariffs, resolved through the associations.
     *
     * The interesting part is what is absent: no SQL, no join written by hand,
     * and one query rather than one per route, because the repository declares
     * an entity graph. This is the shape the delivery panel will read once it
     * stops going to ps_rutas.
     */
    @Operation(summary = "Rutas y tarifas de un club, vía Spring Data")
    @GetMapping("/routes/{clubNumber}")
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> routesOf(@PathVariable Integer clubNumber) {
        List<Map<String, Object>> out = routes
            .findByClub_ClubNumberAndActiveTrueOrderByCode(clubNumber).stream()
            .map(CatalogDiagController::describe)
            .toList();
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> describe(Route r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code",      r.getCode());
        m.put("name",      r.getName());
        m.put("truckSize", r.getTruckSize());
        m.put("type",      r.getRouteType() == null ? null : r.getRouteType().getCode());
        Map<String, Object> tariffs = new LinkedHashMap<>();
        r.getPrices().forEach(p -> tariffs.put(p.getUnitType().name(), p.getPriceLocal()));
        m.put("tariffs", tariffs);
        return m;
    }

    @Operation(summary = "País y su tasa de cambio vigente")
    @GetMapping("/country/{iso2}")
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public ResponseEntity<?> country(@PathVariable String iso2) {
        return countries.findByIso2(iso2).<ResponseEntity<?>>map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("iso2",             c.getIso2());
            m.put("name",             c.getName());
            m.put("currency",         c.getCurrencyCode());
            m.put("taxName",          c.getTaxName());
            m.put("priceIncludesTax", c.getPriceIncludesTax());
            m.put("latestRate", rates.findFirstByCountry_Iso2OrderByEffectiveDateDesc(iso2)
                .map(r -> (Object) Map.of("rate", r.getRate(), "date", r.getEffectiveDate().toString()))
                .orElse(null));
            m.put("paymentMethods", countryMethods
                .findByCountry_Iso2AndActiveTrueOrderBySortOrder(iso2).stream()
                .map(cm -> cm.getMethodType().getCode() + " (tender " + cm.getTenderKey() + ")")
                .toList());
            m.put("documentTypes", docTypes
                .findByCountry_Iso2AndActiveTrueOrderByNameEs(iso2).stream()
                .map(d -> d.getCode() + " — " + d.getNameEs())
                .toList());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }
}
