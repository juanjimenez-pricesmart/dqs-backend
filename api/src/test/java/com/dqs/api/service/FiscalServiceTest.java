package com.dqs.api.service;

import com.dqs.api.catalog.source.CatalogSource;
import com.dqs.api.catalog.source.DocTypeInfo;
import com.dqs.api.client.GoSocketClient;
import com.dqs.api.model.City;
import com.dqs.api.model.EconomicActivity;
import com.dqs.api.model.Neighborhood;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationFiscal;
import com.dqs.api.model.Zone;
import com.dqs.api.repository.CityRepository;
import com.dqs.api.repository.EconomicActivityRepository;
import com.dqs.api.repository.NeighborhoodRepository;
import com.dqs.api.repository.QuotationFiscalRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.repository.ZoneRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fiscal data of a quotation, and the four catalogs behind the invoice form.
 *
 * The catalogs are a cache of what GoSocket returns rather than a governed
 * list, which is why the codes are plain columns and not foreign keys: a code
 * arriving for the first time has to save, not fail. Most of these tests are
 * about that — what is remembered, under which key, and what is left alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiscalServiceTest {

    @Mock private GoSocketClient goSocketClient;
    @Mock private CatalogSource catalogSource;
    @Mock private QuotationFiscalRepository fiscalRepository;
    @Mock private QuotationRepository quotationRepository;
    @Mock private CityRepository cityRepository;
    @Mock private ZoneRepository zoneRepository;
    @Mock private NeighborhoodRepository neighborhoodRepository;
    @Mock private EconomicActivityRepository economicActivityRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FiscalService service() {
        FiscalService s = new FiscalService(goSocketClient, objectMapper, catalogSource,
                fiscalRepository, quotationRepository, cityRepository, zoneRepository,
                neighborhoodRepository, economicActivityRepository);
        set(s, "accountCode", "PS-CO");
        set(s, "countryId", "CO");
        set(s, "defaultIdentificationType", "NIT");
        return s;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = FiscalService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    /** Everything the form sends on a full save. */
    private Map<String, Object> payload() {
        Map<String, Object> data = new HashMap<>();
        data.put("quotation_id", 107);
        data.put("membership", "70012345678901");
        data.put("country", "CO");
        data.put("document_type", "1");
        data.put("document_number", "900123456");
        data.put("document_validated", 1);
        data.put("business_name", "ACME SA");
        data.put("address", "CRA 43 N 82 66");
        data.put("phone", "3001234567");
        data.put("email", "compras@acme.co");
        data.put("nrc", "12345");
        data.put("economic_activity_code", "4711");
        data.put("economic_activity_name", "Comercio al por menor");
        data.put("city_code", "08001");
        data.put("city_name", "BARRANQUILLA");
        data.put("zone_code", "01");
        data.put("zone_name", "NORTE");
        data.put("neighborhood_code", "0101");
        data.put("neighborhood_name", "EL PRADO");
        data.put("generate_fiscal", 1);
        data.put("generate_tiquete_electronico", 0);
        return data;
    }

    private QuotationFiscal saved() {
        ArgumentCaptor<QuotationFiscal> captor = ArgumentCaptor.forClass(QuotationFiscal.class);
        verify(fiscalRepository).save(captor.capture());
        return captor.getValue();
    }

    private QuotationFiscal existing() {
        Quotation q = Quotation.builder().id(107L).storeId(6101).build();
        return QuotationFiscal.builder()
                .id(9L).quotation(q).membership("70012345678901").country("CO")
                .documentType("1").documentNumber("900123456").documentValidated(true)
                .businessName("ACME SA").address("CRA 43 N 82 66")
                .phone("3001234567").email("compras@acme.co").nrc("12345")
                .economicActivityCode("4711").cityCode("08001").zoneCode("01")
                .neighborhoodCode("0101").generateFiscal(true).generateElectronicReceipt(false)
                .createdAt(Instant.parse("2026-09-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-09-02T11:00:00Z"))
                .build();
    }

    // ── GoSocket validation ───────────────────────────────────────────────

    @Test
    @DisplayName("a validation carries the club's account and the country it is configured for")
    void validateSendsConfiguredContext() {
        when(goSocketClient.getAccount(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("valid", true, "name", "ACME SA"));

        Map<String, Object> result = service().validateIdentification("900123456", "NIT");

        assertThat(result).containsEntry("valid", true);
        verify(goSocketClient).getAccount("PS-CO", "NIT", "900123456", "CO");
    }

    @Test
    @DisplayName("no document type means the country's default, not an empty one")
    void validateFallsBackToTheDefaultType() {
        service().validateIdentification("900123456", null);
        verify(goSocketClient).getAccount("PS-CO", "NIT", "900123456", "CO");
    }

    @Test
    @DisplayName("a blank document type is treated as absent")
    void validateTreatsBlankTypeAsAbsent() {
        service().validateIdentification("900123456", "   ");
        verify(goSocketClient).getAccount("PS-CO", "NIT", "900123456", "CO");
    }

    @Test
    @DisplayName("an explicit type overrides the default")
    void validateHonoursAnExplicitType() {
        service().validateIdentification("1020304050", "CC");
        verify(goSocketClient).getAccount("PS-CO", "CC", "1020304050", "CO");
    }

    // ── Save ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a quotation with no fiscal record yet gets one, linked to it")
    void saveCreatesTheRecord() {
        Quotation q = Quotation.builder().id(107L).storeId(6101).build();
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.empty());
        when(quotationRepository.getReferenceById(107L)).thenReturn(q);

        assertThat(service().saveFiscalData(payload())).isTrue();

        QuotationFiscal row = saved();
        assertThat(row.getQuotation()).isSameAs(q);
        assertThat(row.getMembership()).isEqualTo("70012345678901");
        assertThat(row.getBusinessName()).isEqualTo("ACME SA");
        assertThat(row.getDocumentNumber()).isEqualTo("900123456");
    }

    @Test
    @DisplayName("a quotation that already has one has it updated, not duplicated")
    void saveUpdatesTheExistingRecord() {
        QuotationFiscal row = existing();
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));
        Map<String, Object> data = payload();
        data.put("business_name", "ACME LTDA");

        service().saveFiscalData(data);

        assertThat(saved()).isSameAs(row);
        assertThat(row.getId()).isEqualTo(9L);
        assertThat(row.getBusinessName()).isEqualTo("ACME LTDA");
        verify(quotationRepository, never()).getReferenceById(any(Long.class));
    }

    @Test
    @DisplayName("a quotation id sent as text is still read as a number")
    void saveParsesTextualQuotationId() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.put("quotation_id", "107");

        service().saveFiscalData(data);

        verify(fiscalRepository).findByQuotation_Id(107L);
    }

    @Test
    @DisplayName("blank fields are stored as absent, not as empty strings")
    void saveStoresBlanksAsNull() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.put("nrc", "");
        data.put("phone", "   ");

        service().saveFiscalData(data);

        // An empty NRC and a missing NRC are the same thing to the provider.
        QuotationFiscal row = saved();
        assertThat(row.getNrc()).isNull();
        assertThat(row.getPhone()).isNull();
    }

    @Test
    @DisplayName("the flags read every shape a caller sends: 0/1, true/false, and the strings")
    void saveReadsFlagsInEveryShape() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        FiscalService service = service();

        Map<String, Object> data = payload();
        data.put("generate_fiscal", 1);
        data.put("generate_tiquete_electronico", 0);
        service.saveFiscalData(data);
        assertThat(lastSaved().getGenerateFiscal()).isTrue();

        data.put("generate_fiscal", true);
        data.put("document_validated", "true");
        data.put("generate_tiquete_electronico", "1");
        service.saveFiscalData(data);
        QuotationFiscal row = lastSaved();
        assertThat(row.getGenerateFiscal()).isTrue();
        assertThat(row.getDocumentValidated()).isTrue();
        assertThat(row.getGenerateElectronicReceipt()).isTrue();

        data.put("generate_fiscal", "0");
        data.put("document_validated", false);
        data.put("generate_tiquete_electronico", "no");
        service.saveFiscalData(data);
        row = lastSaved();
        assertThat(row.getGenerateFiscal()).isFalse();
        assertThat(row.getDocumentValidated()).isFalse();
        assertThat(row.getGenerateElectronicReceipt()).isFalse();
    }

    @Test
    @DisplayName("a flag the caller leaves out is false, not null")
    void saveDefaultsMissingFlagsToFalse() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.remove("generate_fiscal");
        data.remove("document_validated");

        service().saveFiscalData(data);

        // A null would reach a NOT NULL column and fail the write.
        assertThat(lastSaved().getGenerateFiscal()).isFalse();
        assertThat(lastSaved().getDocumentValidated()).isFalse();
    }

    private QuotationFiscal lastSaved() {
        ArgumentCaptor<QuotationFiscal> captor = ArgumentCaptor.forClass(QuotationFiscal.class);
        verify(fiscalRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }


    // ── Catalog cache ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a catalog entry we have not seen before is remembered")
    void saveRemembersNewCatalogEntries() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        when(economicActivityRepository.findByCode("4711")).thenReturn(Optional.empty());
        when(cityRepository.findByCodeAndCountry("08001", "CO")).thenReturn(Optional.empty());
        when(zoneRepository.findByCodeAndCityCode("01", "08001")).thenReturn(Optional.empty());
        when(neighborhoodRepository.findByCodeAndZoneCodeAndCityCode("0101", "01", "08001"))
                .thenReturn(Optional.empty());

        service().saveFiscalData(payload());

        ArgumentCaptor<City> city = ArgumentCaptor.forClass(City.class);
        verify(cityRepository).save(city.capture());
        assertThat(city.getValue().getCode()).isEqualTo("08001");
        assertThat(city.getValue().getName()).isEqualTo("BARRANQUILLA");
        assertThat(city.getValue().getCountry()).isEqualTo("CO");

        ArgumentCaptor<Neighborhood> nb = ArgumentCaptor.forClass(Neighborhood.class);
        verify(neighborhoodRepository).save(nb.capture());
        // A neighbourhood is only unique within its zone AND its city.
        assertThat(nb.getValue().getZoneCode()).isEqualTo("01");
        assertThat(nb.getValue().getCityCode()).isEqualTo("08001");
    }

    @Test
    @DisplayName("an entry we already have has its name refreshed rather than a second row added")
    void saveRefreshesKnownCatalogEntries() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        City known = City.builder().id(3).code("08001").country("CO").name("BARRANQUILLA (viejo)").build();
        when(cityRepository.findByCodeAndCountry("08001", "CO")).thenReturn(Optional.of(known));

        service().saveFiscalData(payload());

        ArgumentCaptor<City> city = ArgumentCaptor.forClass(City.class);
        verify(cityRepository).save(city.capture());
        assertThat(city.getValue()).isSameAs(known);
        assertThat(known.getName()).isEqualTo("BARRANQUILLA");
    }

    @Test
    @DisplayName("the economic activity is keyed on its code alone, unlike the other three")
    void economicActivityIsKeyedOnCodeAlone() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        when(economicActivityRepository.findByCode("4711")).thenReturn(Optional.empty());

        service().saveFiscalData(payload());

        // Its unique index is narrower than the (code, country) pair the cities,
        // zones and neighbourhoods use.
        verify(economicActivityRepository).findByCode("4711");
        ArgumentCaptor<EconomicActivity> ea = ArgumentCaptor.forClass(EconomicActivity.class);
        verify(economicActivityRepository).save(ea.capture());
        assertThat(ea.getValue().getValue()).isEqualTo("Comercio al por menor");
    }

    @Test
    @DisplayName("a code with no name is not cached — a row with no label is worse than none")
    void saveSkipsCatalogEntriesWithoutAName() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.remove("city_name");
        data.remove("zone_name");
        data.remove("neighborhood_name");
        data.remove("economic_activity_name");

        service().saveFiscalData(data);

        verify(cityRepository, never()).save(any());
        verify(zoneRepository, never()).save(any());
        verify(neighborhoodRepository, never()).save(any());
        verify(economicActivityRepository, never()).save(any());
    }

    @Test
    @DisplayName("each of the four levels is skipped on its own when its code is missing")
    void saveSkipsEachLevelIndependently() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.remove("zone_code");
        data.remove("neighborhood_code");

        service().saveFiscalData(data);

        // The city still caches: a missing zone does not invalidate the level
        // above it.
        verify(cityRepository).save(any());
        verify(zoneRepository, never()).save(any());
        verify(neighborhoodRepository, never()).save(any());
    }

    @Test
    @DisplayName("a save with no quotation id at all does not invent one")
    void saveWithoutAQuotationId() {
        Map<String, Object> data = payload();
        data.remove("quotation_id");
        when(fiscalRepository.findByQuotation_Id(null)).thenReturn(Optional.of(existing()));

        assertThat(service().saveFiscalData(data)).isTrue();

        // The lookup goes out with no id and finds nothing; the write then fails
        // at the database rather than being silently attached to some other quote.
        verify(fiscalRepository).findByQuotation_Id(null);
    }

    @Test
    @DisplayName("a name with no code is not cached either")
    void saveSkipsCatalogEntriesWithoutACode() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        Map<String, Object> data = payload();
        data.remove("city_code");
        data.remove("economic_activity_code");

        service().saveFiscalData(data);

        verify(cityRepository, never()).save(any());
        verify(economicActivityRepository, never()).save(any());
    }

    // ── Read ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the saved record comes back with the display name of every code")
    void readResolvesEveryCatalogName() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        when(economicActivityRepository.findByCode("4711"))
                .thenReturn(Optional.of(EconomicActivity.builder().value("Comercio al por menor").build()));
        when(cityRepository.findByCodeAndCountry("08001", "CO"))
                .thenReturn(Optional.of(City.builder().name("BARRANQUILLA").build()));
        when(zoneRepository.findByCodeAndCityCode("01", "08001"))
                .thenReturn(Optional.of(Zone.builder().name("NORTE").build()));
        when(neighborhoodRepository.findByCodeAndZoneCodeAndCityCode("0101", "01", "08001"))
                .thenReturn(Optional.of(Neighborhood.builder().name("EL PRADO").build()));

        Map<String, Object> row = service().getFiscalDataByQuotation(107L);

        assertThat(row).containsEntry("quotation_id", 107L)
                .containsEntry("business_name", "ACME SA")
                .containsEntry("economic_activity_name", "Comercio al por menor")
                .containsEntry("city_name", "BARRANQUILLA")
                .containsEntry("zone_name", "NORTE")
                .containsEntry("neighborhood_name", "EL PRADO");
    }

    @Test
    @DisplayName("the booleans go out as 0 and 1, which is what the form reads")
    void readSendsFlagsAsNumbers() {
        QuotationFiscal row = existing();
        row.setGenerateFiscal(true);
        row.setGenerateElectronicReceipt(false);
        row.setDocumentValidated(null);
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        Map<String, Object> out = service().getFiscalDataByQuotation(107L);

        assertThat(out).containsEntry("generate_fiscal", 1)
                .containsEntry("generate_tiquete_electronico", 0)
                // A null flag reads as 0, not as null: the form expects a number.
                .containsEntry("document_validated", 0);

        // And the other way round, so neither field is hard-wired to a value.
        row.setGenerateFiscal(false);
        row.setGenerateElectronicReceipt(true);
        assertThat(service().getFiscalDataByQuotation(107L))
                .containsEntry("generate_fiscal", 0)
                .containsEntry("generate_tiquete_electronico", 1);
    }

    @Test
    @DisplayName("a code whose name is not in the cache reads as a missing name, not as a wrong one")
    void readReportsUnresolvedNamesAsAbsent() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(existing()));
        when(economicActivityRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(cityRepository.findByCodeAndCountry(anyString(), anyString())).thenReturn(Optional.empty());
        when(zoneRepository.findByCodeAndCityCode(anyString(), anyString())).thenReturn(Optional.empty());
        when(neighborhoodRepository.findByCodeAndZoneCodeAndCityCode(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> out = service().getFiscalDataByQuotation(107L);

        // The old single query LEFT JOINed all four; a join that did not match
        // looked the same as a code that was never set.
        assertThat(out).containsEntry("city_name", null)
                .containsEntry("zone_name", null)
                .containsEntry("neighborhood_name", null)
                .containsEntry("economic_activity_name", null);
    }

    @Test
    @DisplayName("a record with no codes at all asks the catalogs nothing")
    void readSkipsLookupsWithoutCodes() {
        QuotationFiscal row = existing();
        row.setEconomicActivityCode(null);
        row.setCityCode(null);
        row.setZoneCode(null);
        row.setNeighborhoodCode(null);
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        Map<String, Object> out = service().getFiscalDataByQuotation(107L);

        assertThat(out).containsEntry("city_name", null);
        verify(cityRepository, never()).findByCodeAndCountry(anyString(), anyString());
        verify(economicActivityRepository, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("the timestamps go out as text, and as nothing when unset")
    void readSendsTimestampsAsText() {
        QuotationFiscal row = existing();
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        assertThat(service().getFiscalDataByQuotation(107L))
                .containsEntry("created_at", "2026-09-01T10:00:00Z")
                .containsEntry("updated_at", "2026-09-02T11:00:00Z");

        row.setCreatedAt(null);
        row.setUpdatedAt(null);
        assertThat(service().getFiscalDataByQuotation(107L))
                .containsEntry("created_at", null)
                .containsEntry("updated_at", null);
    }

    @Test
    @DisplayName("a record whose quotation link is gone still reads, with no id")
    void readSurvivesADetachedRecord() {
        QuotationFiscal row = existing();
        row.setQuotation(null);
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.of(row));

        assertThat(service().getFiscalDataByQuotation(107L)).containsEntry("quotation_id", null);
    }

    @Test
    @DisplayName("a quotation with no fiscal record reads as nothing, for the controller to answer 204")
    void readReturnsNullWhenAbsent() {
        when(fiscalRepository.findByQuotation_Id(107L)).thenReturn(Optional.empty());

        assertThat(service().getFiscalDataByQuotation(107L)).isNull();
    }

    // ── Catalogs ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("economic activities go out as code and value, ordered by their label")
    void economicActivitiesGoOutAsCodeAndValue() {
        when(economicActivityRepository.findByCountryOrderByValue("CO")).thenReturn(List.of(
                EconomicActivity.builder().code("4711").value("Comercio al por menor").build()));

        assertThat(service().getEconomicActivities("CO"))
                .containsExactly(Map.of("code", "4711", "value", "Comercio al por menor"));
    }

    @Test
    @DisplayName("cities, zones and neighbourhoods go out as code and name")
    void theOtherThreeGoOutAsCodeAndName() {
        when(cityRepository.findByCountryOrderByName("CO"))
                .thenReturn(List.of(City.builder().code("08001").name("BARRANQUILLA").build()));
        when(zoneRepository.findByCityCodeOrderByName("08001"))
                .thenReturn(List.of(Zone.builder().code("01").name("NORTE").build()));
        when(neighborhoodRepository.findByZoneCodeAndCityCodeOrderByName("01", "08001"))
                .thenReturn(List.of(Neighborhood.builder().code("0101").name("EL PRADO").build()));
        FiscalService service = service();

        assertThat(service.getCities("CO")).containsExactly(Map.of("code", "08001", "name", "BARRANQUILLA"));
        assertThat(service.getZones("08001")).containsExactly(Map.of("code", "01", "name", "NORTE"));
        assertThat(service.getNeighborhoods("01", "08001"))
                .containsExactly(Map.of("code", "0101", "name", "EL PRADO"));
    }

    @Test
    @DisplayName("an empty catalog is an empty list, not a failure")
    void emptyCatalogsAreEmptyLists() {
        when(cityRepository.findByCountryOrderByName("ZZ")).thenReturn(List.of());

        assertThat(service().getCities("ZZ")).isEmpty();
    }

    @Test
    @DisplayName("document types come from the configured source, keeping the legacy id")
    void docTypesComeFromTheCatalogSource() {
        when(catalogSource.documentTypesOfCountry("CO"))
                .thenReturn(List.of(new DocTypeInfo(1, "Factura electrónica", "FE", "01")));

        // Legacy ps_fel or our own table depending on the flag; the id stays
        // the legacy felid either way, because document_type stores it.
        assertThat(service().getDocTypes("CO")).singleElement()
                .satisfies(t -> assertThat(t.id()).isEqualTo(1));
    }
}
