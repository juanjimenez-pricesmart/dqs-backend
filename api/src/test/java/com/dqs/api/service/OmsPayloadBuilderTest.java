package com.dqs.api.service;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationCustomer;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.model.QuotationItemProduct;
import com.dqs.api.model.QuotationItemTaxes;
import com.dqs.api.model.QuotationPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OMS payload.
 *
 * This is the one place in the app where the tax model of thirteen countries
 * has to come out as a single JSON document, and it does it by branching on two
 * things: whether the club's price already includes its tax, and whether the
 * club is Colombian. Those two produce four combinations of unit price, and
 * every figure in a line derives from them — so most of these tests are one
 * combination each, asserted on the numbers rather than on the shape.
 *
 * The rest guard the header: an order sent without a delivery window would
 * reach OMS with blank times, and an order sent without electronic-invoicing
 * data still has to carry the attributes that say so.
 */
class OmsPayloadBuilderTest {

    private final OmsPayloadBuilder builder = new OmsPayloadBuilder();

    // ── Fixtures ──────────────────────────────────────────────────────────

    /** A club whose prices EXCLUDE the tax — the "+" operation, most countries. */
    private Map<String, Object> clubTaxExclusive(String iso2) {
        Map<String, Object> club = new HashMap<>();
        club.put("moneda", "CRC");
        club.put("idioma", "es");
        club.put("pais_iso2", iso2);
        club.put("impuesto_operacion", "+");
        return club;
    }

    /** A club whose prices INCLUDE the tax — the "-" operation, VAT countries. */
    private Map<String, Object> clubVatInclusive(String iso2) {
        Map<String, Object> club = clubTaxExclusive(iso2);
        club.put("impuesto_operacion", "-");
        club.put("moneda", "BBD");
        return club;
    }

    private Map<String, Object> member() {
        Map<String, Object> member = new HashMap<>();
        member.put("firstName", "JUAN");
        member.put("lastName", "PEREZ");
        member.put("addressLine1", "CRA 43 N 82 66");
        member.put("city", "BARRANQUILLA");
        member.put("cellPhone", "3001234567");
        member.put("email", "compras@acme.co");
        return member;
    }

    private Map<String, Object> context(Map<String, Object> club, Map<String, Object> fel) {
        Map<String, Object> context = new HashMap<>();
        context.put("club", club);
        context.put("member", member());
        context.put("user", Map.of("email", "vendedor@pricesmart.com"));
        context.put("exchange_rate", "4150.25");
        if (fel != null) context.put("fel", fel);
        return context;
    }

    private Quotation quotation(int storeId, String tenderKey) {
        Quotation q = Quotation.builder().id(107L).storeId(storeId).userId(1).statusId(3).build();
        q.setCustomer(QuotationCustomer.builder().quotation(q)
                .customerName("ACME").customerMembership("70012345678901").build());
        if (tenderKey != null) {
            q.setPayment(QuotationPayment.builder().quotation(q)
                    .paymentMethodId(tenderKey).paidStatus(1).build());
        }
        return q;
    }

    /**
     * One line: rate 119, of which 19 is tax, quantity 2, and 4 of ICO on the
     * line as a whole. The figures are chosen so every derived price is a
     * distinct round number and a swapped branch is visible at a glance.
     */
    private QuotationItem item(Quotation q) {
        QuotationItem it = QuotationItem.builder().id(500L).quotation(q).productId("1001")
                .qty(new BigDecimal("2")).rate(new BigDecimal("119"))
                .signPrice(new BigDecimal("119")).amount(new BigDecimal("238")).build();
        it.setTaxes(QuotationItemTaxes.builder().item(it)
                .taxPercentage(new BigDecimal("19")).taxFactor(new BigDecimal("19"))
                .taxAmount(new BigDecimal("38")).taxIco(new BigDecimal("4")).build());
        it.setProduct(QuotationItemProduct.builder().item(it)
                .description("ARROZ 5KG").weightPerUnit(new BigDecimal("1.5"))
                .storageType("DRY").soldByWeight("N").build());
        return it;
    }

    private static final String VENTANAS =
            "2026-09-15T11:00:00|2026-09-15T17:00:00|2026-09-15T13:00:00|4471";

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("OrderLine");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> header(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("Extended");
    }

    /** The value of one OrderAttribute by name, or null when it is not there. */
    @SuppressWarnings("unchecked")
    private Object attr(Map<String, Object> payload, String name) {
        return ((List<Map<String, Object>>) payload.get("OrderAttribute")).stream()
                .filter(a -> name.equals(a.get("AttributeName")))
                .map(a -> a.get("AttributeValue"))
                .findFirst().orElse(null);
    }

    // ── The four price combinations ───────────────────────────────────────

    @Test
    @DisplayName("a tax-exclusive club outside Colombia strips the tax out of the unit price")
    void taxExclusiveOutsideColombia() {
        Quotation q = quotation(6401, "110");   // Costa Rica
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS);

        Map<String, Object> line = lines(payload).get(0);
        // 119 with 19 of tax in it: the unit price is 100 …
        assertThat(line).containsEntry("UnitPrice", 100.0);
        // … and the original also drops the 4 of ICO spread over 2 units.
        assertThat(line).containsEntry("OriginalUnitPrice", 98.0);
        assertThat(payload).containsEntry("CurrencyCode", "CRC");
    }

    @Test
    @DisplayName("a VAT-inclusive club keeps the price as displayed and reports the net beneath it")
    void vatInclusive() {
        Quotation q = quotation(8501, "110");   // Barbados
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubVatInclusive("BB"), null), VENTANAS);

        Map<String, Object> line = lines(payload).get(0);
        // The shelf price already includes the VAT, so it goes out untouched…
        assertThat(line).containsEntry("OriginalUnitPrice", 119.0);
        // … and the net is what is left after tax and ICO per unit.
        assertThat(line).containsEntry("UnitPrice", 98.0);
        assertThat(payload).containsEntry("CurrencyCode", "BBD");
    }

    @Test
    @DisplayName("Colombia keeps the displayed price even though its tax is added on")
    void colombiaBehavesLikeVatInclusive() {
        Quotation q = quotation(6101, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CO"), null), VENTANAS);

        Map<String, Object> line = lines(payload).get(0);
        // Its own case in legacy, and the one the Exención column hides for.
        assertThat(line).containsEntry("OriginalUnitPrice", 119.0);
        assertThat(line).containsEntry("UnitPrice", 98.0);
    }

    @Test
    @DisplayName("the tax type is named for the club's own operation")
    void taxTypeFollowsTheClub() {
        Quotation exclusive = quotation(6401, "110");
        Quotation inclusive = quotation(8501, "110");

        assertThat(taxTypeOf(builder.build(exclusive, List.of(item(exclusive)),
                context(clubTaxExclusive("CR"), null), VENTANAS))).isEqualTo("IVA");
        assertThat(taxTypeOf(builder.build(inclusive, List.of(item(inclusive)),
                context(clubVatInclusive("BB"), null), VENTANAS))).isEqualTo("VAT");
    }

    @SuppressWarnings("unchecked")
    private String taxTypeOf(Map<String, Object> payload) {
        List<Map<String, Object>> taxes =
                (List<Map<String, Object>>) lines(payload).get(0).get("OrderLineTaxDetail");
        return (String) taxes.get(0).get("TaxTypeId");
    }

    @Test
    @DisplayName("ICO travels as its own tax line, per unit on the first and whole on the second")
    void icoIsItsOwnTaxLine() {
        Quotation q = quotation(6101, "110");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taxes = (List<Map<String, Object>>) lines(builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CO"), null), VENTANAS))
                .get(0).get("OrderLineTaxDetail");

        // 4 of ICO over 2 units on the IVA line, and the full 4 on the ICO one.
        assertThat(taxes.get(0)).containsEntry("TaxAmount", 2.0).containsEntry("TaxRate", 19.0);
        assertThat(taxes.get(1)).containsEntry("TaxTypeId", "ICO").containsEntry("TaxAmount", 4.0);
    }

    @Test
    @DisplayName("the order totals are the sum of the net lines plus their tax")
    void orderTotalsAddUp() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS);

        // net 98 × 2 = 196, tax 19 × 2 + 4 of ICO = 42.
        assertThat(attr(payload, "LocalSubTotal")).isEqualTo(196.0);
        assertThat(attr(payload, "LocalTaxes")).isEqualTo(42.0);
        assertThat(attr(payload, "OriginalLocalTaxes")).isEqualTo(42.0);
        assertThat(attr(payload, "LocalTotal")).isEqualTo(238.0);
    }

    @Test
    @DisplayName("the taxes also travel as an order-level charge, marked as real rather than informational")
    void taxesTravelAsAnOrderCharge() {
        Quotation q = quotation(6401, "110");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>)
                builder.build(q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS)
                        .get("OrderChargeDetail");

        assertThat(charges).singleElement()
                .satisfies(c -> {
                    assertThat(c).containsEntry("ChargeTotal", 42.0);
                    assertThat(c).containsEntry("ChargeDisplayName", "Taxes");
                    // The line-level charges are informational; this one is not.
                    assertThat(c).containsEntry("IsInformational", false);
                });
    }

    @Test
    @DisplayName("a line with no quantity does not divide the ICO by zero")
    void zeroQuantityDoesNotDivide() {
        Quotation q = quotation(6101, "110");
        QuotationItem it = item(q);
        it.setQty(BigDecimal.ZERO);

        Map<String, Object> line = lines(builder.build(
                q, List.of(it), context(clubTaxExclusive("CO"), null), VENTANAS)).get(0);

        assertThat(line).containsEntry("UnitPrice", 100.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> taxes = (List<Map<String, Object>>) line.get("OrderLineTaxDetail");
        // 0.0, not 0: the conditional's other branch is a double, so the whole
        // expression is, and OMS receives "0.0".
        assertThat(taxes.get(0)).containsEntry("TaxAmount", 0.0);
    }

    @Test
    @DisplayName("a tax-exclusive line with no quantity does not divide the ICO by zero either")
    void zeroQuantityDoesNotDivideOutsideColombia() {
        Quotation q = quotation(6401, "110");
        QuotationItem it = item(q);
        it.setQty(BigDecimal.ZERO);

        Map<String, Object> line = lines(builder.build(
                q, List.of(it), context(clubTaxExclusive("CR"), null), VENTANAS)).get(0);

        // The other side of the same guard: this branch computes the original
        // price rather than the OMS one.
        assertThat(line).containsEntry("OriginalUnitPrice", 100.0);
        assertThat(line).containsEntry("UnitPrice", 100.0);
    }

    @Test
    @DisplayName("a line with no taxes or product row still goes out, with zeros and empty text")
    void bareLineStillGoesOut() {
        Quotation q = quotation(6401, "110");
        QuotationItem bare = QuotationItem.builder().id(501L).quotation(q).productId("1002")
                .qty(new BigDecimal("1")).rate(new BigDecimal("50")).build();

        Map<String, Object> line = lines(builder.build(
                q, List.of(bare), context(clubTaxExclusive("CR"), null), VENTANAS)).get(0);

        assertThat(line).containsEntry("ItemDescription", "");
        assertThat(line).containsEntry("UnitPrice", 50.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> extended = (Map<String, Object>) line.get("Extended");
        assertThat(extended).containsEntry("StorageType", "")
                .containsEntry("EspDescription", "")
                .containsEntry("SoldByWeight", false)
                .containsEntry("Weight", 0.0);
    }

    @Test
    @DisplayName("an item sold by weight says so, and its weight travels in pounds")
    void weightedItemIsFlagged() {
        Quotation q = quotation(6401, "110");
        QuotationItem it = item(q);
        it.getProduct().setSoldByWeight("Y");

        @SuppressWarnings("unchecked")
        Map<String, Object> extended = (Map<String, Object>)
                lines(builder.build(q, List.of(it), context(clubTaxExclusive("CR"), null), VENTANAS))
                        .get(0).get("Extended");

        assertThat(extended).containsEntry("SoldByWeight", true)
                .containsEntry("Weight", 1.5)
                .containsEntry("WeightUOM", "LB");
    }

    @Test
    @DisplayName("lines are numbered from one, in the order they were given")
    void linesAreNumberedInOrder() {
        Quotation q = quotation(6401, "110");
        QuotationItem second = item(q);
        second.setId(501L);
        second.setProductId("1002");

        List<Map<String, Object>> out = lines(builder.build(
                q, List.of(item(q), second), context(clubTaxExclusive("CR"), null), VENTANAS));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsEntry("OrderLineId", 1).containsEntry("ItemId", "1001");
        assertThat(out.get(1)).containsEntry("OrderLineId", 2).containsEntry("ItemId", "1002");
    }

    @Test
    @DisplayName("an order with no lines still builds, with zero totals")
    void emptyOrderStillBuilds() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS);

        assertThat(lines(payload)).isEmpty();
        assertThat(attr(payload, "LocalTotal")).isEqualTo(0.0);
    }

    // ── The delivery window ───────────────────────────────────────────────

    @Test
    @DisplayName("the window's four fields land on the header in OMS's own order")
    void windowFieldsLandInOrder() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> head = header(builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS));

        // The pipe order is picking | end | delivery | id, and each time goes
        // out as UTC with the Z the client does not send.
        assertThat(head).containsEntry("PickingWindowTime", "2026-09-15T11:00:00Z")
                .containsEntry("EndWindowTime", "2026-09-15T17:00:00Z")
                .containsEntry("DeliveryWindowTime", "2026-09-15T13:00:00Z")
                .containsEntry("PickingWindowId", "4471");
    }

    @Test
    @DisplayName("no window at all leaves the four fields blank rather than failing the build")
    void missingWindowLeavesBlanks() {
        Quotation q = quotation(6401, "110");

        for (String ventanas : new String[] { null, "" }) {
            Map<String, Object> head = header(builder.build(
                    q, List.of(item(q)), context(clubTaxExclusive("CR"), null), ventanas));

            // Which is why the modal refuses to send without one: OMS would take
            // an order whose delivery time is the letter Z.
            assertThat(head).containsEntry("PickingWindowId", "")
                    .containsEntry("DeliveryWindowTime", "Z");
        }
    }

    @Test
    @DisplayName("a window with fewer than four fields is padded, not read past its end")
    void shortWindowIsPadded() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> head = header(builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), "2026-09-15T11:00:00|"));

        assertThat(head).containsEntry("PickingWindowTime", "2026-09-15T11:00:00Z")
                .containsEntry("PickingWindowId", "");
    }

    // ── Electronic invoicing ──────────────────────────────────────────────

    @Test
    @DisplayName("a quote with invoicing data says so, and carries the taxpayer's details")
    void felDataTravels() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> fel = new HashMap<>();
        fel.put("nit", "900123456");
        fel.put("doc_type_name", "NIT");
        fel.put("businessname", "ACME SA");
        fel.put("email", "facturas@acme.co");

        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), fel), VENTANAS);

        assertThat(attr(payload, "EI-Required")).isEqualTo(true);
        assertThat(attr(payload, "EI-Identification")).isEqualTo("900123456");
        assertThat(attr(payload, "EI-IdType")).isEqualTo("NIT");
        assertThat(attr(payload, "EI-Name")).isEqualTo("ACME SA");
    }

    @Test
    @DisplayName("a quote without invoicing data still carries the attributes, saying it is not required")
    void withoutFelTheAttributesStillTravel() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS);

        // Omitting them entirely is not the same as sending them empty: OMS
        // reads the absence of EI-Required as unset, not as false.
        assertThat(attr(payload, "EI-Required")).isEqualTo(false);
        assertThat(attr(payload, "EI-Identification")).isEqualTo("");
        assertThat(attr(payload, "EI-IdType")).isEqualTo("NOT_SPECIFIED");
    }

    @Test
    @DisplayName("invoicing data with no document type falls back to unspecified rather than empty")
    void felWithoutADocTypeFallsBack() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), new HashMap<>()), VENTANAS);

        assertThat(attr(payload, "EI-IdType")).isEqualTo("NOT_SPECIFIED");
        assertThat(attr(payload, "EI-Name")).isEqualTo("");
    }

    @Test
    @DisplayName("Guatemala gets three more attributes, and its validation status")
    void guatemalaCarriesItsValidationStatus() {
        Quotation q = quotation(6301, "110");
        Map<String, Object> fel = new HashMap<>();
        fel.put("nit", "900123456");
        fel.put("businessname", "ACME SA");
        fel.put("nit_validated", true);

        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("GT"), fel), VENTANAS);

        assertThat(attr(payload, "EI-ValidationStatus")).isEqualTo("VALIDATED");
        assertThat(attr(payload, "EI-ReferenceNumber")).isEqualTo("");
        assertThat(attr(payload, "taxIDName")).isEqualTo("ACME SA");
    }

    @Test
    @DisplayName("an unvalidated Guatemalan taxpayer says so rather than omitting the field")
    void guatemalaReportsAnUnvalidatedTaxpayer() {
        Quotation q = quotation(6301, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("GT"), new HashMap<>()), VENTANAS);

        assertThat(attr(payload, "EI-ValidationStatus")).isEqualTo("NONVALIDATED");
    }

    @Test
    @DisplayName("a club below the Guatemalan range does not carry them either")
    void clubsBelowGuatemalaDoNotCarryThem() {
        Quotation q = quotation(6101, "110");   // Colombia, under 6300
        Map<String, Object> fel = new HashMap<>();
        fel.put("nit", "900123456");

        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CO"), fel), VENTANAS);

        // Both ends of the range matter: 6401 is above it, this is below.
        assertThat(attr(payload, "EI-ValidationStatus")).isNull();
        assertThat(attr(payload, "EI-Required")).isEqualTo(true);
    }

    @Test
    @DisplayName("no other country carries the Guatemalan attributes")
    void onlyGuatemalaCarriesThem() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> fel = new HashMap<>();
        fel.put("nit", "900123456");

        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), fel), VENTANAS);

        assertThat(attr(payload, "EI-ValidationStatus")).isNull();
        assertThat(attr(payload, "taxIDName")).isNull();
    }

    // ── Header and defaults ───────────────────────────────────────────────

    @Test
    @DisplayName("the tender key comes from the payment row, or is zero when there is none")
    void tenderKeyComesFromThePayment() {
        Quotation withPayment = quotation(6401, "110");
        assertThat(attr(builder.build(withPayment, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS),
                "TenderKey")).isEqualTo("110");

        Quotation withoutPayment = quotation(6401, null);
        assertThat(attr(builder.build(withoutPayment, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS),
                "TenderKey")).isEqualTo("0");

        Quotation blankTender = quotation(6401, "   ");
        assertThat(attr(builder.build(blankTender, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS),
                "TenderKey")).isEqualTo("0");
    }

    @Test
    @DisplayName("the membership is both the membership and the customer id")
    void membershipIsAlsoTheCustomerId() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> head = header(builder.build(
                q, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS));

        assertThat(head).containsEntry("Membership", "70012345678901")
                .containsEntry("CustomerId", "70012345678901")
                .containsEntry("CustomerLanguage", "es")
                .containsEntry("OrderCreatedBy", "vendedor@pricesmart.com");
    }

    @Test
    @DisplayName("a quotation with no customer sends an empty membership rather than failing")
    void missingCustomerSendsAnEmptyMembership() {
        Quotation q = quotation(6401, "110");
        q.setCustomer(null);

        Map<String, Object> head = header(builder.build(
                q, List.of(), context(clubTaxExclusive("CR"), null), VENTANAS));

        assertThat(head).containsEntry("Membership", "").containsEntry("CustomerId", "");
    }

    @Test
    @DisplayName("a member with no contact details on file falls back to placeholders OMS accepts")
    void missingMemberDetailsFallBack() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> context = context(clubTaxExclusive("CR"), null);
        context.put("member", new HashMap<String, Object>());

        Map<String, Object> payload = builder.build(q, List.of(item(q)), context, VENTANAS);

        assertThat(payload).containsEntry("CustomerEmail", "noreply@pricesmart.com")
                .containsEntry("CustomerFirstName", "N/A")
                .containsEntry("CustomerLastName", "N/A")
                .containsEntry("CustomerPhone", "0000000000");
    }

    @Test
    @DisplayName("the ship-to address on each line repeats the member, with the club's country twice")
    void shipToRepeatsTheMember() {
        Quotation q = quotation(6401, "110");
        @SuppressWarnings("unchecked")
        Map<String, Object> shipTo = (Map<String, Object>)
                lines(builder.build(q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS))
                        .get(0).get("ShipToAddress");
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");

        assertThat(address).containsEntry("Address1", "CRA 43 N 82 66")
                // City carries the ISO2 and State the actual city: legacy's own
                // mapping, kept because OMS validates against it.
                .containsEntry("City", "CR")
                .containsEntry("Country", "CR")
                .containsEntry("State", "BARRANQUILLA");
    }

    @Test
    @DisplayName("a context with no club at all falls back to Costa Rica in dollars")
    void missingClubFallsBack() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> context = new HashMap<>();
        context.put("club", "no soy un mapa");
        context.put("member", member());
        context.put("user", Map.of("email", "v@pricesmart.com"));

        Map<String, Object> payload = builder.build(q, List.of(item(q)), context, VENTANAS);

        assertThat(payload).containsEntry("CurrencyCode", "USD");
        assertThat(header(payload)).containsEntry("CustomerLanguage", "es");
    }

    @Test
    @DisplayName("a context with no user sends an empty creator rather than a null one")
    void missingUserSendsAnEmptyCreator() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> context = context(clubTaxExclusive("CR"), null);
        context.remove("user");

        Map<String, Object> head = header(builder.build(q, List.of(), context, VENTANAS));

        assertThat(head).containsEntry("OrderCreatedBy", "").containsEntry("CurrentAssistant", "");
    }

    @Test
    @DisplayName("every order is a confirmed B2B customer order captured on the web")
    void theOrderEnvelopeIsFixed() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> payload = builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS);

        assertThat(payload).containsEntry("IsConfirmed", true).containsEntry("confirmed", true)
                .containsEntry("CustomerTypeId", "DEFAULT");
        assertThat(payload.get("OrderType")).isEqualTo(Map.of("OrderTypeId", "B2B"));
        assertThat(payload.get("DocType")).isEqualTo(Map.of("DocTypeId", "CustomerOrder"));
        assertThat(payload.get("SellingChannel")).isEqualTo(Map.of("SellingChannelId", "Web"));
        assertThat((String) payload.get("CapturedDate"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("every line ships from and to the club that sold it")
    void everyLineShipsFromTheClub() {
        Quotation q = quotation(6401, "110");
        Map<String, Object> line = lines(builder.build(
                q, List.of(item(q)), context(clubTaxExclusive("CR"), null), VENTANAS)).get(0);

        assertThat(line).containsEntry("ShipToLocationId", 6401).containsEntry("UOM", "EA");
        @SuppressWarnings("unchecked")
        Map<String, Object> promising = (Map<String, Object>) line.get("OrderLinePromisingInfo");
        assertThat(promising).containsEntry("ShipFromLocationId", 6401);
    }
}
