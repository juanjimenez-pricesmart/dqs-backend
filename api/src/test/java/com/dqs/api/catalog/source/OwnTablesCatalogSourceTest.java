package com.dqs.api.catalog.source;

import com.dqs.api.catalog.model.Club;
import com.dqs.api.catalog.model.Country;
import com.dqs.api.catalog.model.CountryPaymentMethod;
import com.dqs.api.catalog.model.DeliveryCity;
import com.dqs.api.catalog.model.FiscalDocumentType;
import com.dqs.api.catalog.model.PaymentMethodType;
import com.dqs.api.catalog.model.Route;
import com.dqs.api.catalog.model.RoutePrice;
import com.dqs.api.catalog.model.RouteType;
import com.dqs.api.catalog.model.RouteUnitType;
import com.dqs.api.catalog.repository.CountryPaymentMethodRepository;
import com.dqs.api.catalog.repository.DeliveryCityRepository;
import com.dqs.api.catalog.repository.FiscalDocumentTypeRepository;
import com.dqs.api.catalog.repository.RouteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The catalogs as our own tables hold them — the other half of the seam.
 *
 * The frontend must not be able to tell which source is switched on, so what
 * these tests are really checking is that this one produces the same records
 * LegacyCatalogSourceTest asserts. The difference is in the shape underneath:
 * legacy keeps three tariff tiers as three columns on one row, and here they
 * are three rows that have to be folded back into one record by unit type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnTablesCatalogSourceTest {

    @Mock private RouteRepository routes;
    @Mock private FiscalDocumentTypeRepository documentTypes;
    @Mock private CountryPaymentMethodRepository paymentMethods;
    @Mock private DeliveryCityRepository deliveryCities;

    private OwnTablesCatalogSource source() {
        return new OwnTablesCatalogSource(routes, documentTypes, paymentMethods, deliveryCities);
    }

    private RoutePrice price(RouteUnitType unit, String local, String usd, Integer minimum) {
        return RoutePrice.builder()
                .unitType(unit)
                .priceLocal(local == null ? null : new BigDecimal(local))
                .priceUsd(usd == null ? null : new BigDecimal(usd))
                .minimumQuantity(minimum)
                .build();
    }

    private Route route(RouteType type, List<RoutePrice> prices, String truckSize) {
        Route r = Route.builder()
                .id(1).code("6101 01").name("Barranquilla Dry")
                .club(Club.builder().clubNumber(6101).build())
                .truckSize(truckSize == null ? null : new BigDecimal(truckSize))
                .routeType(type).active(true)
                .build();
        r.setPrices(prices);
        return r;
    }

    private static final RouteType INTERCITY =
            RouteType.builder().code("INTERCITY_DELIVERY").name("Intercity Delivery").build();

    @Test
    @DisplayName("it says which tables it is reading, so the diagnostics can report it")
    void itNamesItsTables() {
        assertThat(source().describe()).contains("QuoteCenter tables").contains("routes");
    }

    @Test
    @DisplayName("the three tariff rows fold back into one record, keyed by tier")
    void tariffRowsFoldIntoOneRecord() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101)).thenReturn(List.of(
                route(INTERCITY, List.of(
                        price(RouteUnitType.FULL_PALLET, "256.00", null, 11),
                        price(RouteUnitType.HALF_PALLET, "43.00", null, 6),
                        price(RouteUnitType.QUARTER_PALLET, "21.00", "5.00", null)),
                        "12.5")));

        RouteInfo route = source().routesOfClub(6101).get(0);

        // The same record the legacy source builds from three columns.
        assertThat(route.id()).isEqualTo("6101 01");
        assertThat(route.truckSize()).isEqualTo(12.5);
        assertThat(route.palletRate()).isEqualTo(256.0);
        assertThat(route.palletMinimum()).isEqualTo(11);
        assertThat(route.halfPalletRate()).isEqualTo(43.0);
        assertThat(route.halfPalletMinimum()).isEqualTo(6);
        assertThat(route.quarterPalletRate()).isEqualTo(21.0);
        assertThat(route.quarterPalletRateUsd()).isEqualTo(5.0);
        assertThat(route.routeTypeCode()).isEqualTo("INTERCITY_DELIVERY");
        assertThat(route.routeTypeName()).isEqualTo("Intercity Delivery");
    }

    @Test
    @DisplayName("a tier with no row of its own reads as no tariff, not as zero")
    void aMissingTierReadsAsAbsent() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101)).thenReturn(List.of(
                route(INTERCITY, List.of(price(RouteUnitType.FULL_PALLET, "256.00", null, 11)), "12.5")));

        RouteInfo route = source().routesOfClub(6101).get(0);

        // Zero would price a half pallet as free.
        assertThat(route.halfPalletRate()).isNull();
        assertThat(route.halfPalletMinimum()).isNull();
        assertThat(route.quarterPalletRate()).isNull();
        assertThat(route.quarterPalletRateUsd()).isNull();
    }

    @Test
    @DisplayName("a tier row with no price reads as absent too")
    void aTierWithoutAPriceReadsAsAbsent() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101)).thenReturn(List.of(
                route(INTERCITY, List.of(price(RouteUnitType.FULL_PALLET, null, null, null)), "12.5")));

        RouteInfo route = source().routesOfClub(6101).get(0);

        assertThat(route.palletRate()).isNull();
        assertThat(route.palletMinimum()).isNull();
    }

    @Test
    @DisplayName("a duplicated tier keeps the first row rather than failing the whole list")
    void aDuplicatedTierKeepsTheFirst() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101)).thenReturn(List.of(
                route(INTERCITY, List.of(
                        price(RouteUnitType.FULL_PALLET, "256.00", null, 11),
                        price(RouteUnitType.FULL_PALLET, "999.00", null, 99)), "12.5")));

        // A unique index should stop this, but a duplicate must not take the
        // whole club's route list down with it.
        assertThat(source().routesOfClub(6101).get(0).palletRate()).isEqualTo(256.0);
    }

    @Test
    @DisplayName("a route with no type or no truck size still comes back")
    void aRouteWithoutTypeOrSizeStillComesBack() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(6101))
                .thenReturn(List.of(route(null, List.of(), null)));

        RouteInfo route = source().routesOfClub(6101).get(0);

        assertThat(route.routeTypeName()).isNull();
        assertThat(route.routeTypeCode()).isNull();
        assertThat(route.truckSize()).isNull();
        assertThat(route.id()).isEqualTo("6101 01");
    }

    @Test
    @DisplayName("a document type maps its own id, name and mask onto the legacy record")
    void documentTypesMapOntoTheLegacyRecord() {
        when(documentTypes.findByCountry_CodeAndActiveTrueOrderByNameEs("CO")).thenReturn(List.of(
                FiscalDocumentType.builder()
                        .id(1).code("NIT").nameEs("NIT").nameEn("Tax ID").inputMask("000000000-0")
                        .country(Country.builder().code("CO").build())
                        .build()));

        DocTypeInfo type = source().documentTypesOfCountry("CO").get(0);

        // The record's field names are legacy's, because the frontend reads one
        // shape whichever source answers.
        assertThat(type.id()).isEqualTo(1);
        assertThat(type.description()).isEqualTo("NIT");
        assertThat(type.typeCode()).isEqualTo("NIT");
        assertThat(type.format()).isEqualTo("000000000-0");
    }

    @Test
    @DisplayName("a payment method carries its tender key and the method type's name")
    void paymentMethodsCarryTheirTenderKey() {
        when(paymentMethods.findByCountry_CodeAndActiveTrueOrderBySortOrderAscMethodType_NameAsc("CO"))
                .thenReturn(List.of(CountryPaymentMethod.builder()
                        .id(5).tenderKey(110)
                        .methodType(PaymentMethodType.builder().code("BANK_TRANSFER").name("Transferencia").build())
                        .build()));

        PaymentMethodInfo method = source().paymentMethodsOfCountry("CO").get(0);

        assertThat(method.id()).isEqualTo(5);
        // The name, not the code: it is what the operator picks from.
        assertThat(method.description()).isEqualTo("Transferencia");
        assertThat(method.tenderKey()).isEqualTo(110);
    }

    @Test
    @DisplayName("a delivery city's numeric id goes out as text, as the legacy cast produced")
    void deliveryCityIdsGoOutAsText() {
        when(deliveryCities.findByCountry_CodeAndActiveTrueOrderByName("CO")).thenReturn(List.of(
                DeliveryCity.builder().id(1).name("BARRANQUILLA")
                        .country(Country.builder().code("CO").build()).build()));

        DeliveryCityInfo city = source().deliveryCitiesOfCountry("CO").get(0);

        assertThat(city.id()).isEqualTo("1");
        assertThat(city.name()).isEqualTo("BARRANQUILLA");
    }

    @Test
    @DisplayName("an empty catalog is an empty list from every one of the four")
    void everyCatalogCanBeEmpty() {
        when(routes.findByClub_ClubNumberAndActiveTrueOrderByCode(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(documentTypes.findByCountry_CodeAndActiveTrueOrderByNameEs(anyString())).thenReturn(List.of());
        when(paymentMethods.findByCountry_CodeAndActiveTrueOrderBySortOrderAscMethodType_NameAsc(anyString()))
                .thenReturn(List.of());
        when(deliveryCities.findByCountry_CodeAndActiveTrueOrderByName(anyString())).thenReturn(List.of());
        OwnTablesCatalogSource source = source();

        assertThat(source.routesOfClub(9999)).isEmpty();
        assertThat(source.documentTypesOfCountry("ZZ")).isEmpty();
        assertThat(source.paymentMethodsOfCountry("ZZ")).isEmpty();
        assertThat(source.deliveryCitiesOfCountry("ZZ")).isEmpty();
    }
}
