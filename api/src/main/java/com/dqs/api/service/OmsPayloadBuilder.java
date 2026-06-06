// service/OmsPayloadBuilder.java
package com.dqs.api.service;

import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class OmsPayloadBuilder {

    public Map<String, Object> build(
            Quotation quotation,
            List<QuotationItem> items,
            Map<String, Object> context,
            String ventanas) {

        // ── Contexto ─────────────────────────────────────────────────────
        Map<String, Object> club    = castMap(context.get("club"));
        Map<String, Object> socio   = castMap(context.get("socio"));
        Map<String, Object> usuario = castMap(context.get("usuario"));
        Map<String, Object> fel     = context.get("fel") != null ? castMap(context.get("fel")) : null;

        double tasaCambio = toDouble(context.get("tasa_cambio"), 1.0);
        String moneda     = str(club.get("moneda"),               "USD");
        String idioma     = str(club.get("idioma"),               "es");
        String paisIso2   = str(club.get("pais_iso2"),            "CR");
        String impOp      = str(club.get("impuesto_operacion"),   "+");

        boolean flagTax = impOp.equals("-"); // VAT = true, IVA = false
        String taxName  = flagTax ? "VAT" : "IVA";

        log.info("[OmsPayloadBuilder] flagTax={} taxName={} moneda={} paisIso2={}",
                flagTax, taxName, moneda, paisIso2);

        // ── Ventanas ─────────────────────────────────────────────────────
        String[] wx = (ventanas != null && !ventanas.isEmpty())
                ? ventanas.split("\\|")
                : new String[]{"", "", "", ""};
        while (wx.length < 4) wx = Arrays.copyOf(wx, 4);

        String w1 = safeStr(wx[2]) + "Z"; // DeliveryWindowTime
        String w2 = safeStr(wx[0]) + "Z"; // PickingWindowTime
        String w3 = safeStr(wx[1]) + "Z"; // EndWindowTime
        String w4 = safeStr(wx[3]);        // PickingWindowId

        // ── Timestamp ────────────────────────────────────────────────────
        String capturedDate = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // ── Order Lines ──────────────────────────────────────────────────
        List<Map<String, Object>> orderLines = new ArrayList<>();
        double subtotal  = 0;
        double impuestos = 0;
        int lineId = 1;

        for (QuotationItem item : items) {
            com.dqs.api.model.QuotationItemTaxes   tx = item.getTaxes();
            com.dqs.api.model.QuotationItemProduct pr = item.getProduct();

            double rate      = toDouble(item.getRate(),                              0);
            double taxFactor = toDouble(tx != null ? tx.getTaxFactor()    : null,    0);
            double taxIco    = toDouble(tx != null ? tx.getTaxIco()       : null,    0);
            double qty       = toDouble(item.getQty(),                               1);
            double weightEa  = toDouble(pr != null ? pr.getWeightEa()     : null,    0);
            double taxPorc   = toDouble(tx != null ? tx.getTaxPorcentaje() : null,   0);
            boolean soldByW  = pr != null && "Y".equals(pr.getSoldByWeight());

            // unitPrice — precio base para display
            double unitPrice = flagTax ? rate : round(rate - taxFactor, 2);

            // rateBase — precio sin impuesto (IVA no-Colombia)
            boolean isColombia = quotation.getStoreId() > 6100 && quotation.getStoreId() < 6199;
            double rateBase;
            if (!flagTax && !isColombia) {
                rateBase = round(rate - taxFactor - (qty != 0 ? taxIco / qty : 0), 2);
            } else {
                rateBase = round(rate, 2);
            }

            double unitPriceOms = (flagTax || isColombia)
                    ? round(unitPrice - taxFactor - (qty != 0 ? taxIco / qty : 0), 2)
                    : round(unitPrice, 2);

            double unitPricePerUOM = (flagTax || isColombia)
                    ? unitPrice - (qty != 0 ? taxIco / qty : 0)
                    : rateBase;

            double sellPriceBefore = (flagTax || isColombia)
                    ? round(unitPrice - taxFactor - (qty != 0 ? taxIco / qty : 0), 2)
                    : unitPrice;

            double chargeTotal = unitPricePerUOM;

            // Acumulados
            double precioBase = round(rate - taxFactor - (qty != 0 ? taxIco / qty : 0), 2);
            subtotal  += round(precioBase * qty, 2);
            impuestos += round(taxFactor * qty + taxIco, 2);

            // ── Extended ──────────────────────────────────────────────────
            Map<String, Object> extended = new LinkedHashMap<>();
            extended.put("Weight",               round(weightEa, 2));
            extended.put("SoldByWeight",         soldByW);
            extended.put("WeightUOM",            "LB");
            extended.put("StorageType",          pr != null ? safeStr(pr.getStorageType()) : "");
            extended.put("EspDescription",       pr != null ? safeStr(pr.getDescription()) : "");
            extended.put("UnitPricePerUOM",      unitPricePerUOM);
            extended.put("SellPriceBeforeTaxes", sellPriceBefore);
            extended.put("USDUnitPricePerUOM",   "0.0");
            extended.put("USDUnitPrice",         "0.0");

            // ── OrderLinePromisingInfo ─────────────────────────────────────
            Map<String, Object> promisingInfo = new LinkedHashMap<>();
            promisingInfo.put("ShipFromLocationId", quotation.getStoreId());

            // ── ShipToAddress ─────────────────────────────────────────────
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("FirstName",  str(socio.get("firstName"),    "N/A"));
            address.put("LastName",   str(socio.get("lastName"),     "N/A"));
            address.put("Address2",   "NA");
            address.put("Address1",   str(socio.get("addressLine1"), "N/A"));
            address.put("City",       paisIso2);
            address.put("State",      str(socio.get("city"),         "N/A"));
            address.put("PostalCode", "1234");
            address.put("Country",    paisIso2);
            address.put("Phone",      str(socio.get("cellPhone"),    "0000000000"));
            address.put("Email",      str(socio.get("email"),        "noreply@pricesmart.com"));

            Map<String, Object> shipTo = new LinkedHashMap<>();
            shipTo.put("Address", address);

            // ── DeliveryMethod ────────────────────────────────────────────
            Map<String, Object> deliveryMethod = new LinkedHashMap<>();
            deliveryMethod.put("DeliveryMethodId", "PickUpAtStore");

            // ── OrderLineChargeDetail ─────────────────────────────────────
            Map<String, Object> chargeExtended = new LinkedHashMap<>();
            chargeExtended.put("UsdChargeAmount", null);

            Map<String, Object> chargeType = new LinkedHashMap<>();
            chargeType.put("ChargeTypeId", "Surcharge");

            Map<String, Object> chargeDetail = new LinkedHashMap<>();
            chargeDetail.put("ChargeTotal",          chargeTotal);
            chargeDetail.put("ChargeDisplayName",    "POSSignPrice");
            chargeDetail.put("ChargeType",           chargeType);
            chargeDetail.put("OriginalChargeAmount", chargeTotal);
            chargeDetail.put("Extended",             chargeExtended);
            chargeDetail.put("IsInformational",      true);

            // ── OrderLineTaxDetail ────────────────────────────────────────
            Map<String, Object> taxExtended1 = new LinkedHashMap<>();
            taxExtended1.put("UsdTaxAmount", "0");

            Map<String, Object> taxDetail1 = new LinkedHashMap<>();
            taxDetail1.put("TaxAmount",      qty != 0 ? round(taxIco / qty, 2) : 0);
            taxDetail1.put("TaxRate",        taxPorc);
            taxDetail1.put("TaxTypeId",      taxName);
            taxDetail1.put("Extended",       taxExtended1);
            taxDetail1.put("IsInformational", true);

            Map<String, Object> taxExtended2 = new LinkedHashMap<>();
            taxExtended2.put("UsdTaxAmount", null);

            Map<String, Object> taxDetail2 = new LinkedHashMap<>();
            taxDetail2.put("TaxAmount",      taxIco);
            taxDetail2.put("TaxRate",        0);
            taxDetail2.put("TaxTypeId",      "ICO");
            taxDetail2.put("Extended",       taxExtended2);
            taxDetail2.put("IsInformational", true);

            // ── OrderLine ─────────────────────────────────────────────────
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("OrderLineId",             lineId);
            line.put("ItemId",                  item.getProductId());
            line.put("Quantity",                item.getQty());
            line.put("UnitPrice",               unitPriceOms);
            line.put("OriginalUnitPrice",       rateBase);
            line.put("ShipToLocationId",        quotation.getStoreId());
            line.put("ItemDescription",         pr != null ? safeStr(pr.getDescription()) : "");
            line.put("DeliveryMethod",          deliveryMethod);
            line.put("Extended",                extended);
            line.put("OrderLinePromisingInfo",  promisingInfo);
            line.put("ShipToAddress",           shipTo);
            line.put("OrderLineChargeDetail",   List.of(chargeDetail));
            line.put("OrderLineTaxDetail",      List.of(taxDetail1, taxDetail2));
            line.put("UOM",                     "EA");

            orderLines.add(line);
            lineId++;
        }

        subtotal  = round(subtotal, 2);
        double total = round(subtotal + impuestos, 2);

        log.info("[OmsPayloadBuilder] subtotal={} impuestos={} total={} lines={}",
                subtotal, round(impuestos, 2), total, orderLines.size());

        // ── OrderAttributes ───────────────────────────────────────────────
        List<Map<String, Object>> attributes = new ArrayList<>();
        String paymentMethodId = quotation.getPayment() != null ? quotation.getPayment().getPaymentMethodId() : "0";
        attributes.add(attr("TenderKey",         str(paymentMethodId, "0")));
        attributes.add(attr("LocalSubTotal",      subtotal));
        attributes.add(attr("LocalTotal",         total));
        attributes.add(attr("LocalTaxes",         round(impuestos, 2)));
        attributes.add(attr("OriginalLocalTaxes", round(impuestos, 2)));

        if (fel != null) {
            attributes.add(attr("EI-Identification", str(fel.get("nit"),           "")));
            attributes.add(attr("EI-IdType",         str(fel.get("doc_type_name"), "NOT_SPECIFIED")));
            attributes.add(attr("EI-Name",           str(fel.get("businessname"),  "")));
            attributes.add(attr("EI-Email",          str(fel.get("email"),         "")));
            attributes.add(attr("EI-Required",       true));

            if (quotation.getStoreId() >= 6300 && quotation.getStoreId() <= 6399) {
                boolean nitValidated = Boolean.TRUE.equals(fel.get("nit_validated"));
                attributes.add(attr("EI-ValidationStatus",
                        nitValidated ? "VALIDATED" : "NONVALIDATED"));
                attributes.add(attr("EI-ReferenceNumber", ""));
                attributes.add(attr("taxIDName", str(fel.get("businessname"), "")));
            }
        } else {
            attributes.add(attr("EI-Identification", ""));
            attributes.add(attr("EI-IdType",         "NOT_SPECIFIED"));
            attributes.add(attr("EI-Name",           ""));
            attributes.add(attr("EI-Email",          ""));
            attributes.add(attr("EI-Required",       false));
        }

        // ── OrderChargeDetail ─────────────────────────────────────────────
        Map<String, Object> orderChargeType = new LinkedHashMap<>();
        orderChargeType.put("ChargeTypeId", "Surcharge");

        Map<String, Object> orderChargeExtended = new LinkedHashMap<>();
        orderChargeExtended.put("UsdChargeAmount", "0");

        Map<String, Object> orderCharge = new LinkedHashMap<>();
        orderCharge.put("ChargeTotal",       round(impuestos, 2));
        orderCharge.put("ChargeDisplayName", "Taxes");
        orderCharge.put("ChargeType",        orderChargeType);
        orderCharge.put("Extended",          orderChargeExtended);
        orderCharge.put("IsInformational",   false);

        // ── Extended header ───────────────────────────────────────────────
        Map<String, Object> extendedHeader = new LinkedHashMap<>();
        extendedHeader.put("OrderCreatedBy",          str(usuario.get("email"), ""));
        String membership = quotation.getCustomer() != null ? safeStr(quotation.getCustomer().getCustomerMembership()) : "";
        extendedHeader.put("Membership",              membership);
        extendedHeader.put("CustomerId",              membership);
        extendedHeader.put("AllowPartialFulfillment", true);
        extendedHeader.put("CurrentAssistant",        str(usuario.get("email"), ""));
        extendedHeader.put("DeliveryWindowTime",      w1);
        extendedHeader.put("PickingWindowId",         w4);
        extendedHeader.put("PickingWindowTime",       w2);
        extendedHeader.put("EndWindowTime",           w3);
        extendedHeader.put("CustomerLanguage",        idioma);

        // ── Payload final ─────────────────────────────────────────────────
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("CapturedDate",      capturedDate);
        payload.put("CurrencyCode",      moneda);
        payload.put("IsConfirmed",       true);
        payload.put("confirmed",         true);
        payload.put("CustomerEmail",     str(socio.get("email"),     "noreply@pricesmart.com"));
        payload.put("CustomerFirstName", str(socio.get("firstName"), "N/A"));
        payload.put("CustomerLastName",  str(socio.get("lastName"),  "N/A"));
        payload.put("CustomerPhone",     str(socio.get("cellPhone"), "0000000000"));
        payload.put("CustomerTypeId",    "DEFAULT");
        payload.put("Extended",          extendedHeader);
        payload.put("OrderType",         mapOf("OrderTypeId",       "B2B"));
        payload.put("DocType",           mapOf("DocTypeId",         "CustomerOrder"));
        payload.put("SellingChannel",    mapOf("SellingChannelId",  "Web"));
        payload.put("OrderLine",         orderLines);
        payload.put("OrderChargeDetail", List.of(orderCharge));
        payload.put("OrderAttribute",    attributes);

        return payload;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> attr(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AttributeName",  name);
        m.put("AttributeValue", value != null ? value : "");
        return m;
    }

    private Map<String, Object> mapOf(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double toDouble(Object val, double def) {
        if (val == null) return def;
        try { return Double.parseDouble(val.toString()); }
        catch (Exception e) { return def; }
    }

    private String str(Object val, String def) {
        if (val == null) return def;
        String s = val.toString().trim();
        return s.isEmpty() ? def : s;
    }

    private String safeStr(String val) {
        return val != null ? val : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object val) {
        if (val instanceof Map) return (Map<String, Object>) val;
        return new LinkedHashMap<>();
    }
}