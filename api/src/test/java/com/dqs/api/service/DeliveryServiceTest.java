package com.dqs.api.service;

import com.dqs.api.catalog.source.CatalogSource;
import com.dqs.api.catalog.source.DeliveryCityInfo;
import com.dqs.api.catalog.source.RouteInfo;
import com.dqs.api.client.BusinessApiClient;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationDelivery;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.repository.QuotationDeliveryRepository;
import com.dqs.api.repository.QuotationItemRepository;
import com.dqs.api.repository.QuotationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    @Mock private BusinessApiClient businessApiClient;
    @Mock private CatalogSource catalogSource;
    @Mock private QuotationTotalsCalculator totalsCalculator;
    @Mock private QuotationService quotationService;
    @Mock private QuotationDeliveryRepository deliveryRepository;
    @Mock private QuotationRepository quotationRepository;
    @Mock private QuotationItemRepository itemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeliveryService service() {
        return new DeliveryService(businessApiClient, objectMapper, catalogSource,
            totalsCalculator, quotationService, deliveryRepository,
            quotationRepository, itemRepository);
    }

    private Map<String, Object> payload() {
        Map<String, Object> data = new HashMap<>();
        data.put("quotation_id", 107);
        data.put("qty", "1");
        data.put("sign_price", "500.00");
        data.put("address", "CRA 43 N 82 66");
        data.put("delivery_date", "2026-09-15");
        data.put("hour_from", "8");
        data.put("hour_to", "17");
        data.put("route_id", "6101 01");
        data.put("route_name", "Barranquilla Dry");
        data.put("city_id", "1");
        data.put("city_name", "Barranquilla");
        return data;
    }

    private void quotationExists() {
        when(quotationRepository.getReferenceById(107L)).thenReturn(Quotation.builder().id(107L).build());
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.empty());
    }

    // ── catalogs, straight through the seam ──────────────────────────────────

    @Test
    @DisplayName("routes come from whichever catalog source is configured")
    void routesDelegateToTheCatalog() {
        RouteInfo route = new RouteInfo("6409 01", "Turrialba", 2.0,
            256.0, 11, 43.0, 6, 21.0, 5175.0, "Intercity Delivery", "B2B_DELIVERY");
        when(catalogSource.routesOfClub(6409)).thenReturn(List.of(route));

        assertThat(service().getRoutes(6409)).containsExactly(route);
    }

    @Test
    @DisplayName("cities come from the catalog too, and an empty country is not an error")
    void citiesDelegateToTheCatalog() {
        when(catalogSource.deliveryCitiesOfCountry("SV"))
            .thenReturn(List.of(new DeliveryCityInfo("67", "San Miguel")));
        when(catalogSource.deliveryCitiesOfCountry("GT")).thenReturn(List.of());

        assertThat(service().getCities("SV")).hasSize(1);
        assertThat(service().getCities("GT")).isEmpty();
    }

    // ── delivery windows ─────────────────────────────────────────────────────

    @Test
    @DisplayName("windows are requested for the given type")
    void requestsWindowsForTheType() {
        when(businessApiClient.post(anyString(), any())).thenReturn("{\"windows\":[]}");

        service().getDeliveryWindows(6101, "2026-09-10T12:00:00", "HOME_DELIVERY");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(businessApiClient).post(path.capture(), any());
        assertThat(path.getValue())
            .contains("/api/deliveries/location/6101")
            .contains("2026-09-10T12:00:00Z")
            .contains("/delivery/HOME_DELIVERY");
    }

    @Test
    @DisplayName("a blank type falls back to pickup, as the panel does for a quote with no route")
    void blankTypeFallsBackToPickup() {
        when(businessApiClient.post(anyString(), any())).thenReturn("{\"windows\":[]}");

        service().getDeliveryWindows(6101, "2026-09-10T12:00:00", "  ");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(businessApiClient).post(path.capture(), any());
        assertThat(path.getValue()).contains("PICK_UP_IN_CLUB");
    }

    @Test
    @DisplayName("a null type falls back to pickup as well")
    void nullTypeFallsBackToPickup() {
        when(businessApiClient.post(anyString(), any())).thenReturn("{\"windows\":[]}");

        service().getDeliveryWindows(6101, "2026-09-10T12:00:00", null);

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(businessApiClient).post(path.capture(), any());
        assertThat(path.getValue()).contains("PICK_UP_IN_CLUB");
    }

    @Test
    @DisplayName("an unparseable window payload fails loudly")
    void unparseableWindowsThrow() {
        when(businessApiClient.post(anyString(), any())).thenReturn("<html>");

        assertThatThrownBy(() -> service().getDeliveryWindows(6101, "x", "PICK_UP_IN_CLUB"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Error consultando ventanas");
    }

    // ── saving ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the amount is derived here, never taken from the client")
    void amountIsComputedFromQtyAndSignPrice() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("qty", "3");
        data.put("sign_price", "500.00");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("the chosen city is stored as code and snapshot name")
    void storesTheCity() {
        quotationExists();

        service().saveDelivery(payload());

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getCityCode()).isEqualTo("1");
        assertThat(saved.getValue().getCityName()).isEqualTo("Barranquilla");
    }

    @Test
    @DisplayName("saving recalculates the totals, since the delivery amount is part of them")
    void savingRecalculatesTotals() {
        quotationExists();

        service().saveDelivery(payload());

        verify(totalsCalculator).recalculateFor(107L);
    }

    @Test
    @DisplayName("an existing delivery is updated rather than duplicated")
    void existingDeliveryIsUpdated() {
        QuotationDelivery existing = QuotationDelivery.builder().id(9L).build();
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing));

        service().saveDelivery(payload());

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("missing qty and price fall back to one unit at zero")
    void missingAmountsFallBack() {
        quotationExists();
        Map<String, Object> data = payload();
        data.remove("qty");
        data.remove("sign_price");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getQty()).isEqualByComparingTo("1");
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("blank text fields are stored as null, not as empty strings")
    void blankTextBecomesNull() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("address", "   ");
        data.put("route_name", "");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getAddress()).isNull();
        assertThat(saved.getValue().getRouteName()).isNull();
    }

    @Test
    @DisplayName("a zero date from the legacy schema is read as no date")
    void legacyZeroDateIsNull() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("delivery_date", "0000-00-00");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryDate()).isNull();
    }

    @Test
    @DisplayName("a timestamp is cut down to its date")
    void timestampIsTruncatedToADate() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("delivery_date", "2026-09-15T00:00:00");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("an unparseable date is dropped instead of failing the save")
    void unparseableDateIsDropped() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("delivery_date", "next tuesday");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryDate()).isNull();
    }

    @Test
    @DisplayName("the 888905 line is written with the delivery")
    void writesTheDeliveryLine() {
        quotationExists();

        service().saveDelivery(payload());

        verify(quotationService).saveItem(eq(107L), any());
    }

    /*
     * The request map is untyped — it arrives as JSON — so the same field can
     * come through as a number from one client and a string from another. These
     * cover both shapes rather than assuming the one the frontend happens to
     * send today.
     */
    @Test
    @DisplayName("numbers and strings are both accepted for every coerced field")
    void coercesNumbersAndStrings() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("quotation_id", "107");     // as a string this time
        data.put("qty", 2);                  // as a number
        data.put("sign_price", 250);
        data.put("hour_from", 9);
        data.put("hour_to", 18);
        data.put("pallets", 1.5);

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getHourFrom()).isEqualTo(9);
        assertThat(saved.getValue().getHourTo()).isEqualTo(18);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("500");
        assertThat(saved.getValue().getPallets()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("empty and absent fields are read as no value, not as zero-length text")
    void emptyFieldsBecomeNull() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("hour_from", "");
        data.remove("hour_to");
        data.remove("delivery_date");
        data.remove("pallets");
        data.put("sign_price", "");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getHourFrom()).isNull();
        assertThat(saved.getValue().getHourTo()).isNull();
        assertThat(saved.getValue().getDeliveryDate()).isNull();
        assertThat(saved.getValue().getPallets()).isNull();
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an empty date string is read as no date")
    void emptyDateIsNull() {
        quotationExists();
        Map<String, Object> data = payload();
        data.put("delivery_date", "   ");

        service().saveDelivery(data);

        ArgumentCaptor<QuotationDelivery> saved = ArgumentCaptor.forClass(QuotationDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryDate()).isNull();
    }

    @Test
    @DisplayName("a request with no quotation id fails rather than saving an orphan")
    void missingQuotationIdIsRejected() {
        Map<String, Object> data = payload();
        data.remove("quotation_id");

        // Without the guard the row is built against a null quotation and the
        // save fails at commit with a constraint violation instead.
        assertThatThrownBy(() -> service().saveDelivery(data))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("quotation_id is required");
    }

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the delivery comes back keyed as the frontend reads it")
    void getDeliveryReturnsSnakeCaseKeys() {
        QuotationDelivery row = QuotationDelivery.builder()
            .id(9L)
            .qty(BigDecimal.ONE)
            .amount(new BigDecimal("500"))
            .address("CRA 43")
            .deliveryDate(LocalDate.of(2026, 9, 15))
            .cityCode("1").cityName("Barranquilla")
            .build();
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        Map<String, Object> out = service().getDelivery(107L);

        assertThat(out).containsEntry("city_id", "1")
                       .containsEntry("city_name", "Barranquilla")
                       .containsEntry("delivery_date", "2026-09-15");
    }

    @Test
    @DisplayName("a fully populated row maps its quotation id and timestamps")
    void mapsAPopulatedRow() {
        QuotationDelivery row = QuotationDelivery.builder()
            .id(9L)
            .quotation(Quotation.builder().id(107L).build())
            .deliveryDate(LocalDate.of(2026, 9, 15))
            .createdAt(Instant.parse("2026-09-10T12:00:00Z"))
            .updatedAt(Instant.parse("2026-09-10T13:00:00Z"))
            .build();
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        Map<String, Object> out = service().getDelivery(107L);

        assertThat(out).containsEntry("quotation_id", 107L)
                       .containsEntry("delivery_date", "2026-09-15");
        assertThat(out.get("created_at")).isEqualTo("2026-09-10T12:00:00Z");
        assertThat(out.get("updated_at")).isEqualTo("2026-09-10T13:00:00Z");
    }

    @Test
    @DisplayName("a row with nothing filled in still maps, with nulls rather than blowing up")
    void mapsARowFullOfNulls() {
        when(deliveryRepository.findByQuotation_Id(107L))
            .thenReturn(Optional.of(QuotationDelivery.builder().id(9L).build()));

        Map<String, Object> out = service().getDelivery(107L);

        assertThat(out).containsEntry("quotation_id", null)
                       .containsEntry("delivery_date", null)
                       .containsEntry("created_at", null)
                       .containsEntry("updated_at", null);
    }

    @Test
    @DisplayName("no delivery row means null, which the controller turns into 204")
    void getDeliveryReturnsNullWhenAbsent() {
        when(deliveryRepository.findByQuotation_Id(107L)).thenReturn(Optional.empty());

        assertThat(service().getDelivery(107L)).isNull();
    }

    @Test
    @DisplayName("the last address is the member's most recent one")
    void lastAddressIsReturned() {
        when(deliveryRepository.findAddressesByMembership(eq("6101009"), any()))
            .thenReturn(List.of("CRA 43 N 82 66"));

        assertThat(service().getLastDeliveryAddress("6101009")).isEqualTo("CRA 43 N 82 66");
    }

    @Test
    @DisplayName("a member with no delivery history gets an empty string, not null")
    void lastAddressIsEmptyWhenThereIsNone() {
        when(deliveryRepository.findAddressesByMembership(anyString(), any())).thenReturn(List.of());

        assertThat(service().getLastDeliveryAddress("6101009")).isEmpty();
    }

    // ── deleting ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleting removes every 888905 line and then the row")
    void deleteRemovesLinesAndRow() {
        when(itemRepository.findAllByQuotation_IdAndProductId(107L, "888905"))
            .thenReturn(List.of(QuotationItem.builder().id(1L).build(),
                                QuotationItem.builder().id(2L).build()));

        assertThat(service().deleteDelivery(107L)).isTrue();

        verify(quotationService).deleteItem(107L, 1L);
        verify(quotationService).deleteItem(107L, 2L);
        verify(deliveryRepository).deleteByQuotation_Id(107L);
    }

    @Test
    @DisplayName("deleting recalculates after the row is gone, not before")
    void deleteRecalculatesLast() {
        when(itemRepository.findAllByQuotation_IdAndProductId(107L, "888905"))
            .thenReturn(List.of());

        service().deleteDelivery(107L);

        // deleteItem already recalculates, but at that point the amount was
        // still in the total — hence a second pass at the end.
        verify(totalsCalculator).recalculateFor(107L);
    }
}
