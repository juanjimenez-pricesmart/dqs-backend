package com.dqs.api.catalog.source;

import com.dqs.api.catalog.model.Route;
import com.dqs.api.catalog.model.RoutePrice;
import com.dqs.api.catalog.model.RouteUnitType;
import com.dqs.api.catalog.repository.CountryPaymentMethodRepository;
import com.dqs.api.catalog.repository.FiscalDocumentTypeRepository;
import com.dqs.api.catalog.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogs read from our own tables in Azure SQL, through Spring Data.
 *
 * Active only when {@code azure.datasource.enabled} is true. Note what is not
 * here: no SQL. The tables are properly modelled, so a derived query name does
 * the work a hand-written SELECT used to.
 *
 * Every method is transactional against {@code catalogTransactionManager} — the
 * Azure one. The default manager belongs to MySQL, and a lazy association
 * touched outside a catalog transaction would fail.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "azure.datasource.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AzureCatalogSource implements CatalogSource {

    private final RouteRepository routes;
    private final FiscalDocumentTypeRepository documentTypes;
    private final CountryPaymentMethodRepository paymentMethods;

    @Override
    public String describe() {
        return "Azure SQL (routes, fiscal_document_types, country_payment_methods)";
    }

    /**
     * The tariff tiers arrive as rows rather than as columns, so they are pivoted
     * back into the flat shape the delivery panel reads. The repository declares
     * an entity graph over prices and type, so this is one query however many
     * routes come back.
     */
    @Override
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public List<RouteInfo> routesOfClub(Integer clubNumber) {
        return routes.findByClub_ClubNumberAndActiveTrueOrderByCode(clubNumber)
                     .stream().map(AzureCatalogSource::toRoute).toList();
    }

    @Override
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public List<DocTypeInfo> documentTypesOfCountry(String countryIso2) {
        return documentTypes.findByCountry_Iso2AndActiveTrueOrderByNameEs(countryIso2).stream()
            .map(d -> new DocTypeInfo(d.getId(), d.getNameEs(), d.getCode(), d.getInputMask()))
            .toList();
    }

    @Override
    @Transactional(transactionManager = "catalogTransactionManager", readOnly = true)
    public List<PaymentMethodInfo> paymentMethodsOfCountry(String countryIso2) {
        return paymentMethods.findByCountry_Iso2AndActiveTrueOrderBySortOrderAscMethodType_NameAsc(countryIso2).stream()
            .map(m -> new PaymentMethodInfo(m.getId(), m.getMethodType().getName(), m.getTenderKey()))
            .toList();
    }

    private static RouteInfo toRoute(Route r) {
        Map<RouteUnitType, RoutePrice> byTier = r.getPrices().stream()
            .collect(Collectors.toMap(RoutePrice::getUnitType, Function.identity(), (a, b) -> a));

        return new RouteInfo(
            r.getCode(),
            r.getName(),
            r.getTruckSize() == null ? null : r.getTruckSize().doubleValue(),
            local(byTier.get(RouteUnitType.FULL_PALLET)),
            minimum(byTier.get(RouteUnitType.FULL_PALLET)),
            local(byTier.get(RouteUnitType.HALF_PALLET)),
            minimum(byTier.get(RouteUnitType.HALF_PALLET)),
            local(byTier.get(RouteUnitType.QUARTER_PALLET)),
            usd(byTier.get(RouteUnitType.QUARTER_PALLET)),
            r.getRouteType() == null ? null : r.getRouteType().getName(),
            r.getRouteType() == null ? null : r.getRouteType().getCode());
    }

    private static Double  local(RoutePrice p)   { return p == null ? null : dbl(p.getPriceLocal()); }
    private static Double  usd(RoutePrice p)     { return p == null ? null : dbl(p.getPriceUsd()); }
    private static Integer minimum(RoutePrice p) { return p == null ? null : p.getMinimumQuantity(); }
    private static Double  dbl(BigDecimal b)     { return b == null ? null : b.doubleValue(); }
}
