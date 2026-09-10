package com.dqs.api.catalog;

import com.dqs.api.catalog.model.Country;
import com.dqs.api.catalog.model.CountryPaymentMethod;
import com.dqs.api.catalog.model.ExchangeRate;
import com.dqs.api.catalog.model.FiscalDocumentType;
import com.dqs.api.catalog.model.PaymentMethodType;
import com.dqs.api.catalog.model.Route;
import com.dqs.api.catalog.model.RoutePrice;
import com.dqs.api.catalog.model.RouteType;
import com.dqs.api.catalog.model.RouteUnitType;
import com.dqs.api.catalog.repository.ClubRepository;
import com.dqs.api.catalog.repository.CountryPaymentMethodRepository;
import com.dqs.api.catalog.repository.CountryRepository;
import com.dqs.api.catalog.repository.ExchangeRateRepository;
import com.dqs.api.catalog.repository.FiscalDocumentTypeRepository;
import com.dqs.api.catalog.repository.PaymentMethodTypeRepository;
import com.dqs.api.catalog.repository.RoutePriceRepository;
import com.dqs.api.catalog.repository.RouteRepository;
import com.dqs.api.catalog.repository.RouteTypeRepository;
import com.dqs.api.catalog.source.CatalogSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog diagnostics, which only exist while the own-tables migration is
 * in flight: they are how we confirm the new tables carry what the legacy ones
 * did. The controller is behind quotecenter.catalogs.own-tables, so on the
 * legacy source it is not even registered.
 *
 * The route view is the part with real logic — it flattens each route's price
 * rows into a tariff map keyed by unit type, and a route with no type at all
 * has to survive that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogDiagControllerTest {

    @Mock private CountryRepository countries;
    @Mock private ClubRepository clubs;
    @Mock private RouteTypeRepository routeTypes;
    @Mock private RouteRepository routes;
    @Mock private RoutePriceRepository routePrices;
    @Mock private FiscalDocumentTypeRepository docTypes;
    @Mock private PaymentMethodTypeRepository methodTypes;
    @Mock private CountryPaymentMethodRepository countryMethods;
    @Mock private ExchangeRateRepository rates;
    @Mock private CatalogSource catalogSource;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CatalogDiagController(
                countries, clubs, routeTypes, routes, routePrices,
                docTypes, methodTypes, countryMethods, rates, catalogSource)).build();
    }

    private static Route route(RouteType type, List<RoutePrice> prices) {
        Route r = Route.builder()
                .id(1).code("6101 01").name("Barranquilla Dry")
                .truckSize(new BigDecimal("12")).routeType(type).active(true)
                .build();
        r.setPrices(prices);
        return r;
    }

    private static RoutePrice price(RouteUnitType unit, String local) {
        return RoutePrice.builder().unitType(unit).priceLocal(new BigDecimal(local)).build();
    }

    @Test
    @DisplayName("the counts name the source they came from, alongside every table")
    void countsNameTheSource() throws Exception {
        when(catalogSource.describe()).thenReturn("OwnTablesCatalogSource");
        when(countries.count()).thenReturn(13L);
        when(clubs.count()).thenReturn(47L);
        when(routeTypes.count()).thenReturn(4L);
        when(routes.count()).thenReturn(120L);
        when(routePrices.count()).thenReturn(360L);
        when(docTypes.count()).thenReturn(31L);
        when(methodTypes.count()).thenReturn(9L);
        when(countryMethods.count()).thenReturn(58L);
        when(rates.count()).thenReturn(13L);

        mvc.perform(get("/api/v1/diag/catalog"))
                .andExpect(status().isOk())
                // Which source answered is the first thing to know: the same
                // endpoint reads differently depending on the flag.
                .andExpect(jsonPath("$.source").value("OwnTablesCatalogSource"))
                .andExpect(jsonPath("$.countries").value(13))
                .andExpect(jsonPath("$.route_prices").value(360))
                .andExpect(jsonPath("$.country_payment_methods").value(58));
    }

    @Test
    @DisplayName("a club's routes come back with their tariffs keyed by unit type")
    void routesFlattenTheirTariffs() throws Exception {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101)).thenReturn(List.of(
                route(RouteType.builder().code("INTERCITY_DELIVERY").name("Intercity Delivery").build(),
                        List.of(price(RouteUnitType.FULL_PALLET, "256"),
                                price(RouteUnitType.HALF_PALLET, "43")))));

        mvc.perform(get("/api/v1/diag/catalog/routes/6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("6101 01"))
                .andExpect(jsonPath("$[0].truckSize").value(12))
                .andExpect(jsonPath("$[0].type").value("INTERCITY_DELIVERY"))
                // Three columns on legacy's ps_rutas, one row per tier here.
                .andExpect(jsonPath("$[0].tariffs.FULL_PALLET").value(256))
                .andExpect(jsonPath("$[0].tariffs.HALF_PALLET").value(43));
    }

    @Test
    @DisplayName("a route with no type and no prices still describes itself")
    void routeSurvivesMissingTypeAndPrices() throws Exception {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101))
                .thenReturn(List.of(route(null, List.of())));

        mvc.perform(get("/api/v1/diag/catalog/routes/6101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").doesNotExist())
                .andExpect(jsonPath("$[0].tariffs").isEmpty());
    }

    @Test
    @DisplayName("a club with no routes answers an empty list, not a 404")
    void clubWithoutRoutesIsEmpty() throws Exception {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(9999)).thenReturn(List.of());

        mvc.perform(get("/api/v1/diag/catalog/routes/9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a country comes back with its rate, its tenders and its document types")
    void countryGathersEverythingBehindIt() throws Exception {
        when(countries.findByCode("CO")).thenReturn(Optional.of(Country.builder()
                .code("CO").name("Colombia").currencyCode("COP").taxName("IVA")
                .priceIncludesTax(true).build()));
        when(rates.findFirstByCountry_CodeOrderByEffectiveDateDesc("CO")).thenReturn(
                Optional.of(ExchangeRate.builder()
                        .rate(new BigDecimal("4150.25")).effectiveDate(LocalDate.of(2026, 9, 1)).build()));
        when(countryMethods.findByCountry_CodeAndActiveTrueOrderBySortOrderAscMethodType_NameAsc("CO"))
                .thenReturn(List.of(CountryPaymentMethod.builder()
                        .tenderKey(110)
                        .methodType(PaymentMethodType.builder().code("BANK_TRANSFER").build())
                        .build()));
        when(docTypes.findByCountry_CodeAndActiveTrueOrderByNameEs("CO")).thenReturn(
                List.of(FiscalDocumentType.builder().code("NIT").nameEs("NIT").build()));

        mvc.perform(get("/api/v1/diag/catalog/country/CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("COP"))
                // Colombia's prices include the tax, which is what makes its
                // totals arithmetic differ from everyone else's.
                .andExpect(jsonPath("$.priceIncludesTax").value(true))
                .andExpect(jsonPath("$.latestRate.rate").value(4150.25))
                .andExpect(jsonPath("$.latestRate.date").value("2026-09-01"))
                .andExpect(jsonPath("$.paymentMethods[0]").value("BANK_TRANSFER (tender 110)"))
                .andExpect(jsonPath("$.documentTypes[0]").value("NIT — NIT"));
    }

    @Test
    @DisplayName("a country with no exchange rate on record says so, rather than omitting the field")
    void countryWithoutARate() throws Exception {
        when(countries.findByCode("CO")).thenReturn(Optional.of(Country.builder().code("CO").build()));
        when(rates.findFirstByCountry_CodeOrderByEffectiveDateDesc(anyString())).thenReturn(Optional.empty());
        when(countryMethods.findByCountry_CodeAndActiveTrueOrderBySortOrderAscMethodType_NameAsc(anyString()))
                .thenReturn(List.of());
        when(docTypes.findByCountry_CodeAndActiveTrueOrderByNameEs(anyString())).thenReturn(List.of());

        mvc.perform(get("/api/v1/diag/catalog/country/CO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRate").doesNotExist())
                .andExpect(jsonPath("$.paymentMethods").isEmpty());
    }

    @Test
    @DisplayName("an unknown country is a 404")
    void unknownCountryIsNotFound() throws Exception {
        when(countries.findByCode("ZZ")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/diag/catalog/country/ZZ"))
                .andExpect(status().isNotFound());
    }
}
