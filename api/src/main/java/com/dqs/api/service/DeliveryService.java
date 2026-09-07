package com.dqs.api.service;

import com.dqs.api.client.BusinessApiClient;

import com.dqs.api.dto.QuotationItemRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    // Product ID that represents a delivery charge in the quotation items list
    /**
     * Kept as an alias so existing callers keep compiling; the code itself now
     * lives in the SpecialItems registry, next to the traits that explain why
     * this line is treated differently.
     */
    public static final String DELIVERY_PRODUCT_ID = com.dqs.api.util.SpecialItems.DELIVERY;

    private final BusinessApiClient businessApiClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final QuotationService quotationService;

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
    // 1. Upserts quotation_delivery metadata
    // 2. Upserts the 888905 line item in quotation_items (qty=1, rate=amount, tax-exempt)

    public boolean saveDelivery(Map<String, Object> data) {
        Long quotationId = toLong(data.get("quotation_id"));
        log.info("[DeliveryService] saveDelivery quotationId={}", quotationId);

        try {
            // Resolve qty and sign_price — frontend sends these; amount is computed here
            BigDecimal qty       = data.get("qty")        != null ? new BigDecimal(data.get("qty").toString())        : BigDecimal.ONE;
            BigDecimal signPrice = data.get("sign_price") != null ? new BigDecimal(data.get("sign_price").toString()) : BigDecimal.ZERO;
            BigDecimal amount    = qty.multiply(signPrice);

            log.info("[DeliveryService] saveDelivery qty={} signPrice={} amount={}", qty, signPrice, amount);

            // 1. Upsert delivery metadata
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quotation_delivery WHERE quotation_id = ?",
                Integer.class, quotationId);

            BigDecimal pallets = data.get("pallets") != null
                ? new BigDecimal(data.get("pallets").toString()) : null;
            String routeId   = data.get("route_id")   != null ? data.get("route_id").toString()   : null;
            String routeName = data.get("route_name") != null ? data.get("route_name").toString() : null;

            if (count != null && count > 0) {
                jdbcTemplate.update(
                    "UPDATE quotation_delivery SET " +
                    "  qty = ?, sign_price = ?, amount = ?, address = ?, delivery_date = ?, " +
                    "  hour_from = ?, hour_to = ?, ring = ?, box = ?, route_id = ?, route_name = ?, pallets = ? " +
                    "WHERE quotation_id = ?",
                    qty, signPrice, amount,
                    data.get("address"), data.get("delivery_date"),
                    data.get("hour_from"), data.get("hour_to"),
                    data.get("ring"), data.get("box"), routeId, routeName, pallets,
                    quotationId);
            } else {
                jdbcTemplate.update(
                    "INSERT INTO quotation_delivery " +
                    "  (quotation_id, qty, sign_price, amount, address, delivery_date, hour_from, hour_to, ring, box, route_id, route_name, pallets) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    quotationId,
                    qty, signPrice, amount,
                    data.get("address"), data.get("delivery_date"),
                    data.get("hour_from"), data.get("hour_to"),
                    data.get("ring"), data.get("box"), routeId, routeName, pallets);
            }

            // 2. Upsert 888905 line item — tax-exempt, rate = sign_price, qty = user qty
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

        } catch (Exception e) {
            log.error("[DeliveryService] Error saving delivery: {}", e.getMessage());
            return false;
        }
    }

    // ── Routes for a store ────────────────────────────────────────────────────

    /**
     * Routes for a club, with the tariffs the "Costo por ruta" panel shows.
     *
     * Legacy fetches those separately, one request per route selection
     * (orders/getrouteinfo → Model_orders::getrutaid). They come from the same
     * ps_rutas row the list already reads, so they ride along here instead:
     * the panel is informational and the extra round trip bought nothing.
     *
     * The tariff columns are legacy's own names — `14pallet_local` starts with
     * a digit and has to be quoted.
     */
    public List<Map<String, Object>> getRoutes(Integer storeId) {
        log.info("[DeliveryService] getRoutes storeId={}", storeId);
        return jdbcTemplate.queryForList(
            "SELECT A.llave AS id, A.descripcion AS name, A.truck_size AS truckSize, " +
            "A.pallet_local AS palletRate, A.pallet_required AS palletMinimum, " +
            "A.halfpallet_local AS halfPalletRate, A.halfpallet_required AS halfPalletMinimum, " +
            "A.`14pallet_local` AS quarterPalletRate, A.`14pallet_usd` AS quarterPalletRateUsd, " +
            "TR.nombre AS routeTypeName, TR.codigo AS routeTypeCode " +
            "FROM ps_rutas A " +
            "LEFT JOIN ps_tipos_ruta TR ON A.tipo_ruta_id = TR.id AND TR.status = 'A' " +
            "WHERE A.ps_tienda_id = ? AND A.status = 'A' " +
            "ORDER BY A.llave",
            storeId);
    }

    /**
     * The address of the member's most recent delivery, to prefill the form.
     *
     * Legacy's query is Model_orders::getLastDeliveryAddress, and it orders
     * `fecha_entrega ASC LIMIT 1` — which returns the OLDEST address, not the
     * last one, despite the name. That reads as a typo rather than a decision:
     * a member who moved would be offered an address they left years ago,
     * forever. This orders DESC. Flip it if byte-parity matters more.
     *
     * Only sold quotations count (status 3), as legacy does.
     */
    public String getLastDeliveryAddress(String membership) {
        log.info("[DeliveryService] getLastDeliveryAddress membership={}", membership);
        List<String> found = jdbcTemplate.queryForList(
            "SELECT d.address " +
            "FROM quotations q " +
            "JOIN quotation_customers c ON c.quotation_id = q.id " +
            "JOIN quotation_delivery  d ON d.quotation_id = q.id " +
            "WHERE c.customer_membership = ? AND q.status_id = 3 " +
            "  AND d.address IS NOT NULL AND d.address <> '' " +
            "ORDER BY d.delivery_date DESC LIMIT 1",
            String.class, membership);
        return found.isEmpty() ? "" : found.get(0);
    }

    // ── Get delivery metadata ─────────────────────────────────────────────────

    public Map<String, Object> getDelivery(Long quotationId) {
        log.info("[DeliveryService] getDelivery quotationId={}", quotationId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM quotation_delivery WHERE quotation_id = ?", quotationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Delete delivery — removes metadata + the 888905 line item ────────────

    public boolean deleteDelivery(Long quotationId) {
        log.info("[DeliveryService] deleteDelivery quotationId={}", quotationId);
        try {
            // Remove the 888905 line item from quotation_items
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM quotation_items WHERE quotation_id = ? AND product_id = ?",
                quotationId, DELIVERY_PRODUCT_ID);

            for (Map<String, Object> row : rows) {
                Long itemId = toLong(row.get("id"));
                quotationService.deleteItem(quotationId, itemId);
            }

            // Remove delivery metadata
            jdbcTemplate.update("DELETE FROM quotation_delivery WHERE quotation_id = ?", quotationId);

            return true;
        } catch (Exception e) {
            log.error("[DeliveryService] Error deleting delivery: {}", e.getMessage());
            return false;
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }
}
