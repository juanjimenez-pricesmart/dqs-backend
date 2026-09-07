package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;
import com.dqs.api.dto.QuotationItemRequest;
import com.dqs.api.model.QuotationDelivery;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.repository.QuotationDeliveryRepository;
import com.dqs.api.repository.QuotationItemRepository;
import com.dqs.api.repository.QuotationRepository;
import com.dqs.api.catalog.source.CatalogSource;
import com.dqs.api.catalog.source.RouteInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivery details of a quotation, and the 888905 line that carries its charge.
 *
 * `quotation_delivery` is ours and is mapped as an entity. The route catalog
 * comes from CatalogSource, which reads either the legacy `ps_rutas` or our own
 * tables depending on configuration — this service does not know which.
 *
 * The public shape of this service is unchanged: Maps in, Maps out, snake_case
 * keys matching the column names the frontend already reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    /**
     * Kept as an alias so existing callers keep compiling; the code itself now
     * lives in the SpecialItems registry, next to the traits that explain why
     * this line is treated differently.
     */
    public static final String DELIVERY_PRODUCT_ID = com.dqs.api.util.SpecialItems.DELIVERY;

    /** Quotation status for a completed sale. */
    private static final int STATUS_SOLD = 3;

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;
    private final CatalogSource catalogSource;
    private final QuotationService quotationService;
    private final QuotationDeliveryRepository deliveryRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository itemRepository;

    // ── Business API: delivery windows ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeliveryWindows(Integer clubId, String dateTime, String deliveryType) {
        log.info("[DeliveryService] getDeliveryWindows clubId={} dateTime={} type={}", clubId, dateTime, deliveryType);
        String type = (deliveryType != null && !deliveryType.isBlank()) ? deliveryType : "PICK_UP_IN_CLUB";
        String path = "/api/deliveries/location/" + clubId
                + "/window-place-time/" + dateTime + "Z"
                + "/delivery/" + type;
        String response = businessApiClient.post(path, Map.of());
        try {
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("[DeliveryService] Error parsing delivery windows: {}", e.getMessage());
            throw new RuntimeException("Error consultando ventanas de entrega: " + e.getMessage());
        }
    }

    // ── Save delivery — owns the full lifecycle ───────────────────────────────
    // 1. Upserts the quotation_delivery row
    // 2. Upserts the 888905 line item in quotation_items (rate = sign_price, tax-exempt)

    @Transactional
    public boolean saveDelivery(Map<String, Object> data) {
        Long quotationId = toLong(data.get("quotation_id"));
        log.info("[DeliveryService] saveDelivery quotationId={}", quotationId);

        // The client sends qty and sign_price; amount is always derived here
        // so a client cannot store a total that disagrees with its parts.
        BigDecimal qty       = toDecimal(data.get("qty"),        BigDecimal.ONE);
        BigDecimal signPrice = toDecimal(data.get("sign_price"), BigDecimal.ZERO);
        BigDecimal amount    = qty.multiply(signPrice);

        log.info("[DeliveryService] saveDelivery qty={} signPrice={} amount={}", qty, signPrice, amount);

        QuotationDelivery delivery = deliveryRepository.findByQuotation_Id(quotationId)
            .orElseGet(() -> QuotationDelivery.builder()
                .quotation(quotationRepository.getReferenceById(quotationId))
                .build());

        delivery.setQty(qty);
        delivery.setSignPrice(signPrice);
        delivery.setAmount(amount);
        delivery.setAddress(toStringOrNull(data.get("address")));
        delivery.setDeliveryDate(toLocalDate(data.get("delivery_date")));
        delivery.setHourFrom(toInteger(data.get("hour_from")));
        delivery.setHourTo(toInteger(data.get("hour_to")));
        delivery.setRing(toStringOrNull(data.get("ring")));
        delivery.setBox(toStringOrNull(data.get("box")));
        delivery.setRouteId(toStringOrNull(data.get("route_id")));
        delivery.setRouteName(toStringOrNull(data.get("route_name")));
        delivery.setPallets(toDecimal(data.get("pallets"), null));

        deliveryRepository.save(delivery);

        QuotationItemRequest itemReq = QuotationItemRequest.builder()
            .productId(DELIVERY_PRODUCT_ID)
            .description("Delivery")
            .qty(qty)
            .rate(signPrice)
            .signPrice(signPrice)
            .taxPorcentaje(BigDecimal.ZERO)
            .taxFactor(BigDecimal.ZERO)
            .taxIco(BigDecimal.ZERO)
            .build();
        quotationService.saveItem(quotationId, itemReq);

        log.info("[DeliveryService] saveDelivery OK quotationId={} amount={}", quotationId, amount);
        return true;
    }

    // ── Routes for a store ────────────────────────────────────────────────────

    /**
     * Routes for a club, with the tariffs the "Costo por ruta" panel shows.
     *
     * Which tables answer depends on quotecenter.catalogs.own-tables; both return the
     * same shape. See CatalogSource.
     */
    public List<RouteInfo> getRoutes(Integer storeId) {
        log.info("[DeliveryService] getRoutes storeId={}", storeId);
        return catalogSource.routesOfClub(storeId);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** The address of the member's most recent delivery, to prefill the form. */
    @Transactional(readOnly = true)
    public String getLastDeliveryAddress(String membership) {
        log.info("[DeliveryService] getLastDeliveryAddress membership={}", membership);
        List<String> found = deliveryRepository.findAddressesByMembership(membership, PageRequest.of(0, 1));
        return found.isEmpty() ? "" : found.get(0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDelivery(Long quotationId) {
        log.info("[DeliveryService] getDelivery quotationId={}", quotationId);
        return deliveryRepository.findByQuotation_Id(quotationId).map(this::toMap).orElse(null);
    }

    // ── Delete delivery — removes the row + the 888905 line item ─────────────

    @Transactional
    public boolean deleteDelivery(Long quotationId) {
        log.info("[DeliveryService] deleteDelivery quotationId={}", quotationId);
        itemRepository.findAllByQuotation_IdAndProductId(quotationId, DELIVERY_PRODUCT_ID)
            .stream().map(QuotationItem::getId).toList()
            .forEach(itemId -> quotationService.deleteItem(quotationId, itemId));

        deliveryRepository.deleteByQuotation_Id(quotationId);
        return true;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /**
     * The row as the API has always exposed it: snake_case keys named after the
     * columns, because that is what the frontend reads (src/api/deliveries.ts).
     * Moving to JPA changed the persistence layer, not the contract.
     *
     * Dates go out as ISO strings rather than as temporal objects so the shape
     * does not depend on Jackson's date configuration — the frontend does
     * `delivery_date.slice(0, 10)`, which needs a string.
     */
    private Map<String, Object> toMap(QuotationDelivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            d.getId());
        m.put("quotation_id",  d.getQuotation() != null ? d.getQuotation().getId() : null);
        m.put("qty",           d.getQty());
        m.put("sign_price",    d.getSignPrice());
        m.put("amount",        d.getAmount());
        m.put("address",       d.getAddress());
        m.put("delivery_date", d.getDeliveryDate() != null ? d.getDeliveryDate().toString() : null);
        m.put("hour_from",     d.getHourFrom());
        m.put("hour_to",       d.getHourTo());
        m.put("ring",          d.getRing());
        m.put("box",           d.getBox());
        m.put("route_id",      d.getRouteId());
        m.put("route_name",    d.getRouteName());
        m.put("pallets",       d.getPallets());
        m.put("logcargueid",   d.getLogCargueId());
        m.put("created_at",    d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        m.put("updated_at",    d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
        return m;
    }

    // ── Coercion of the untyped request map ───────────────────────────────────

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private Integer toInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        String s = val.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    private BigDecimal toDecimal(Object val, BigDecimal fallback) {
        if (val == null) return fallback;
        String s = val.toString().trim();
        return s.isEmpty() ? fallback : new BigDecimal(s);
    }

    private String toStringOrNull(Object val) {
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Empty strings and MySQL's `0000-00-00` both mean "no date". The old raw
     * INSERT let them through to the driver; a LocalDate column cannot, and a
     * quote with a placeholder date is not worth failing a save over.
     */
    private LocalDate toLocalDate(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        if (s.isEmpty() || s.startsWith("0000-00-00")) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            log.warn("[DeliveryService] Unparseable delivery_date '{}', stored as null", s);
            return null;
        }
    }
}
