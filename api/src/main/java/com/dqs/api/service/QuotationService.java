package com.dqs.api.service;

import com.dqs.api.dto.*;
import com.dqs.api.exception.InvalidPresetAmountException;
import com.dqs.api.exception.InvalidSeasonException;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
import com.dqs.api.util.MapUtils;
import com.dqs.api.util.SpecialItems;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.*;
import com.dqs.api.repository.QuotationItemRepository;
import com.dqs.api.repository.QuotationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationService {


    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final com.dqs.api.repository.QuotationCancelRepository quotationCancelRepository;
    private final OmsService omsService;
    private final OmsPayloadBuilder omsPayloadBuilder;
    private final ObjectMapper objectMapper;
    private final ItemService itemService;
    private final QuotationTotalsCalculator totalsCalculator;
    private final PresetAmountService presetAmountService;
    private final SeasonService seasonService;

    @Value("${idp.base-url}")
    private String idpBaseUrl;

    @Value("${idp.client-id}")
    private String clientId;

    @Value("${idp.client-secret}")
    private String clientSecret;

    @Value("${dqs.base-url}")
    private String dqsBaseUrl;

    /**
     * How long a quotation is valid for. It is both the default expiry on
     * creation and what one press of "Extender fecha" adds, because legacy
     * hardcodes 21 in each place separately and they have never disagreed.
     */
    private static final int EXPIRY_DAYS = 21;

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public QuotationResponse createQuotation(CreateQuotationRequest req) {
        log.info("[QuotationService] createQuotation store_id={} user_id={}", req.getStoreId(), req.getUserId());

        Quotation quotation = Quotation.builder()
                .storeId(req.getStoreId())
                .userId(req.getUserId())
                .statusId(1)
                .expiryDate(parseExpiry(req.getExpiryDate()))
                .build();

        QuotationCustomer customer = QuotationCustomer.builder()
                .quotation(quotation)
                .customerName(req.getCustomerName())
                .customerMembership(req.getCustomerMembership())
                .customerBusiness(req.getCustomerBusiness())
                .build();

        QuotationTotals totals = QuotationTotals.builder()
                .quotation(quotation)
                .taxRate(req.getTaxRate())
                .applyTaxes(req.getApplyTaxes())
                .excent(0)
                .grossAmount(req.getGrossAmount())
                .netAmount(req.getNetAmount())
                .discount(req.getDiscount())
                .vatChargeRate(req.getVatChargeRate())
                .vatCharge(req.getVatCharge())
                .serviceChargeRate(req.getServiceChargeRate())
                .serviceCharge(req.getServiceCharge())
                .deliveryAmount(BigDecimal.ZERO)
                .build();

        quotation.setCustomer(customer);
        quotation.setTotals(totals);

        Quotation saved = quotationRepository.save(quotation);
        log.info("[QuotationService] quotation created id={}", saved.getId());
        return toResponse(saved);
    }

    // ── Get ───────────────────────────────────────────────────────────────

    public QuotationResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    // ── Submit ────────────────────────────────────────────────────────────

    @Transactional
    public QuotationResponse submitQuotation(Long id, SubmitQuotationRequest req) {
        log.info("[QuotationService] submitQuotation id={} submitted_by={}", id, req.getSubmittedBy());

        Quotation quotation = findOrThrow(id);
        if (quotation.getStatusId() != 1) {
            throw new QuotationAlreadySubmittedException(id, quotation.getStatusId());
        }

        quotation.setStatusId(2);

        String methodId = req.getPaymentMethodId() != null ? String.valueOf(req.getPaymentMethodId()) : "0";
        ensurePayment(quotation).setPaymentMethodId(methodId);

        return toResponse(quotationRepository.save(quotation));
    }

    // ── Save item ─────────────────────────────────────────────────────────

    @Transactional
    public QuotationItemResponse saveItem(Long quotationId, QuotationItemRequest req) {
        log.info("[QuotationService] saveItem quotation_id={} product_id={}", quotationId, req.getProductId());

        Quotation quotation = findOrThrow(quotationId);

        BigDecimal qty       = MapUtils.coalesce(req.getQty(),       BigDecimal.ZERO);
        BigDecimal signPrice = MapUtils.coalesce(req.getSignPrice(),  BigDecimal.ZERO);
        BigDecimal taxFactor = MapUtils.coalesce(req.getTaxFactor(),  BigDecimal.ZERO);
        BigDecimal pl        = MapUtils.coalesce(req.getPl(),         BigDecimal.ONE);
        BigDecimal weightPerUnit  = MapUtils.coalesce(req.getWeightPerUnit(),   BigDecimal.ZERO);
        BigDecimal taxIco    = MapUtils.coalesce(req.getTaxIco(),     BigDecimal.ZERO);

        BigDecimal amount       = qty.multiply(signPrice).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal taxAmount    = qty.multiply(taxFactor).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal weightResult = qty.multiply(weightPerUnit).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal palletQuantity   = pl.compareTo(BigDecimal.ZERO) != 0
                ? qty.divide(pl, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        QuotationItem item = quotationItemRepository
                .findByQuotation_IdAndProductId(quotationId, req.getProductId())
                .orElseGet(() -> {
                    QuotationItem newItem = QuotationItem.builder()
                            .quotation(quotation)
                            .productId(req.getProductId())
                            .comment("")
                            .includeImage(0)
                            .priceVariation(0)
                            .build();
                    newItem.setTaxes(QuotationItemTaxes.builder().item(newItem).build());
                    newItem.setProduct(QuotationItemProduct.builder().item(newItem).build());
                    return newItem;
                });

        item.setQty(qty);
        item.setRate(req.getRate());
        item.setSignPrice(signPrice);
        item.setAmount(amount);

        QuotationItemTaxes taxes = item.getTaxes();
        taxes.setTaxPercentage(req.getTaxPercentage());
        taxes.setTaxFactor(taxFactor);
        taxes.setTaxAmount(taxAmount);
        taxes.setTaxIco(taxIco);
        taxes.setExemptionPercentage(BigDecimal.ZERO);
        taxes.setExemptionAmount(BigDecimal.ZERO);

        QuotationItemProduct product = item.getProduct();
        product.setDescription(req.getDescription());
        product.setPackSize(req.getPackSize());
        product.setPl(pl);
        product.setWeightPerUnit(weightPerUnit);
        product.setWeightResult(weightResult);
        product.setPalletQuantity(palletQuantity);
        product.setOnhand(req.getOnhand());
        product.setSoldByWeight(req.getSoldByWeight());
        product.setRecipe(req.getRecipe());
        product.setStorageType(req.getStorageType());
        product.setPicture1(req.getPicture1());
        product.setDepartment(req.getDepartment());
        product.setCategory(req.getCategory());

        QuotationItem saved = quotationItemRepository.save(item);
        log.info("[QuotationService] item saved id={} quotation_id={} product_id={}", saved.getId(), quotationId, saved.getProductId());
        // The lines changed, so the totals are stale by definition.
        totalsCalculator.recalculateFor(quotationId);
        return toItemResponse(saved);
    }

    // ── Bulk add (Copy & Paste Excel) ─────────────────────────────────────

    /**
     * Adds many lines at once from pasted spreadsheet rows.
     *
     * The client sends only code and quantity — the catalog lookup happens
     * here, exactly as legacy's orders/csvcreardetalle does. Doing it in the
     * browser would mean two round trips per code, so fifty pasted rows would
     * be a hundred requests.
     *
     * Legacy quantity rules are preserved: an empty or zero quantity becomes 1,
     * and a code already on the quotation has its quantity SUMMED. saveItem on
     * its own replaces the quantity — the single-add path sums in the browser
     * before calling it — so the sum is done here.
     *
     * Codes the catalog does not know are reported back rather than failing the
     * whole paste; legacy lists them under "Items no copiados". The delivery
     * SKU is reported separately: adding it as a plain line would leave a row
     * with no quotation_delivery record behind it, which no screen can then
     * edit. It belongs to the Envio form.
     */
    @Transactional
    public java.util.Map<String, Object> addItemsBulk(Long quotationId, Integer clubId,
                                                      List<java.util.Map<String, Object>> lines) {
        findOrThrow(quotationId);

        List<String> added = new java.util.ArrayList<>();
        List<String> notFound = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();

        for (java.util.Map<String, Object> line : lines) {
            Object codeRaw = line.get("productId");
            if (codeRaw == null || codeRaw.toString().isBlank()) continue;
            String code = codeRaw.toString().trim();

            BigDecimal qty = BigDecimal.ONE;
            Object qtyRaw = line.get("qty");
            if (qtyRaw != null && !qtyRaw.toString().isBlank()) {
                try {
                    BigDecimal parsed = new BigDecimal(qtyRaw.toString().trim());
                    if (parsed.compareTo(BigDecimal.ZERO) > 0) qty = parsed;
                } catch (NumberFormatException ignored) {
                    // Legacy treats anything unparseable as the default of 1.
                }
            }

            // Owned by its own panel: a plain add would leave a line with no
            // backing record behind it.
            if (com.dqs.api.util.SpecialItems.has(code, com.dqs.api.util.SpecialItems.Trait.OWNED_BY_PANEL)) {
                skipped.add(code);
                continue;
            }

            java.util.Map<String, Object> catalog;
            try {
                catalog = itemService.getItemByCode(code, clubId);
            } catch (Exception e) {
                log.warn("[QuotationService] bulk: catalog lookup failed for {}: {}", code, e.getMessage());
                notFound.add(code);
                continue;
            }
            if (catalog == null || catalog.get("item_code") == null) {
                notFound.add(code);
                continue;
            }

            // Sum into an existing line, as legacy does for a repeated code.
            BigDecimal finalQty = quotationItemRepository
                    .findByQuotation_IdAndProductId(quotationId, code)
                    .map(existing -> MapUtils.coalesce(existing.getQty(), BigDecimal.ZERO))
                    .orElse(BigDecimal.ZERO)
                    .add(qty);

            saveItem(quotationId, toItemRequest(catalog, finalQty));
            added.add(code);
        }

        log.info("[QuotationService] addItemsBulk quotation_id={} added={} notFound={} skipped={}",
                quotationId, added.size(), notFound.size(), skipped.size());
        // The lines changed, so the totals are stale by definition.
        totalsCalculator.recalculateFor(quotationId);
        return java.util.Map.of("added", added, "notFound", notFound, "skipped", skipped);
    }

    /** Maps a catalog row onto the same request shape a single add uses. */
    private QuotationItemRequest toItemRequest(java.util.Map<String, Object> c, BigDecimal qty) {
        QuotationItemRequest req = new QuotationItemRequest();
        req.setProductId(str(c.get("item_code")));
        req.setDescription(str(c.get("description")).trim());
        req.setQty(qty);
        req.setRate(dec(c.get("sellPrice")));
        req.setSignPrice(dec(c.get("sign_price")));
        // The catalog carries IVA and VAT side by side; whichever is populated
        // is the one that applies, matching what the single-add path sends.
        req.setTaxPercentage(nonZero(dec(c.get("iva_Percent")), dec(c.get("vat_Percent"))));
        req.setTaxFactor(nonZero(dec(c.get("iva_Amount")), dec(c.get("vat_Amount"))));
        req.setTaxIco(dec(c.get("ico_Amount")));
        req.setPackSize(dec(c.get("cu_EA")));
        req.setPl(dec(c.get("pl")));
        req.setWeightPerUnit(dec(c.get("weight_EA_KG")));
        req.setOnhand(dec(c.get("quantityOnHand")));
        req.setSoldByWeight(str(c.get("soldByWeight")));
        req.setRecipe(str(c.get("recipe")));
        req.setStorageType(str(c.get("storageType")));
        req.setPicture1(str(c.get("image1")).trim());
        req.setDepartment(str(c.get("department")));
        req.setCategory(str(c.get("category")));
        return req;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Both call sites pass dec(), which answers ZERO for a missing column, so there is no null to guard against. */
    private static BigDecimal nonZero(BigDecimal a, BigDecimal b) {
        return a.compareTo(BigDecimal.ZERO) != 0 ? a : b;
    }

    // ── Update item qty ───────────────────────────────────────────────────

    /**
     * Narrow path kept for the existing PATCH .../items/{itemId}/qty endpoint,
     * which the lines table calls on every inline quantity edit.
     */
    @Transactional
    public QuotationItemResponse updateItemQty(Long quotationId, Long itemId, java.util.Map<String, Object> body) {
        return updateItem(quotationId, itemId, body);
    }

    /**
     * Updates one line: quantity, exemption percentage, per-item comment, the
     * print-image flag and — for a product sold at fixed denominations — the
     * chosen amount. Every key is optional, so a caller sends only what it
     * changed.
     *
     * Legacy spreads this across three endpoints — orders/saveitemqty,
     * orders/savecomment and orders/saveincludepic — each re-reading and
     * re-writing the same row. One endpoint for one row keeps the recompute
     * rules in a single place. Legacy's saveitemqty carries its itemAmount too,
     * so the gift card rides the same call there as it does here.
     */
    @Transactional
    public QuotationItemResponse updateItem(Long quotationId, Long itemId, java.util.Map<String, Object> body) {
        findOrThrow(quotationId);
        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new QuotationNotFoundException(itemId));
        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new IllegalArgumentException("Item " + itemId + " does not belong to quotation " + quotationId);
        }

        // Before the quantity, because it sets the unit price the quantity is
        // then multiplied by. The other order would price the line off the old
        // amount and leave it there until the next edit.
        if (body.get("presetAmount") != null) {
            applyPresetAmount(item, new BigDecimal(body.get("presetAmount").toString()));
        }

        if (body.get("qty") != null) {
            applyQty(item, new BigDecimal(body.get("qty").toString()));
        }

        // The exemption is recomputed on EVERY call, not just when a percentage
        // is sent, because it is derived from tax_amount and a quantity change
        // moves tax_amount. Legacy does the same — OrdersItemModel::updateItem
        // sets excent_amount unconditionally, outside the `if (!empty($exemp))`
        // guard. Skipping it left the exempt amount stale after a qty edit.
        BigDecimal pct = body.get("exemp") != null
                ? new BigDecimal(body.get("exemp").toString())
                : (item.getTaxes() != null ? MapUtils.coalesce(item.getTaxes().getExemptionPercentage(), BigDecimal.ZERO) : BigDecimal.ZERO);
        applyExemption(item, pct);

        // Item Info drawer: the per-item comment and the "include image in the
        // quote" flag. Both columns already existed and were read-only.
        if (body.get("comment") != null) {
            String comment = body.get("comment").toString();
            // orders_item.icomments is VARCHAR(500); truncate rather than let
            // the driver reject the write.
            item.setComment(comment.length() > 500 ? comment.substring(0, 500) : comment);
        }
        if (body.get("includeImage") != null) {
            Object raw = body.get("includeImage");
            boolean include = raw instanceof Boolean b ? b : !"0".equals(raw.toString()) && !"false".equalsIgnoreCase(raw.toString());
            item.setIncludeImage(include ? 1 : 0);
        }

        QuotationItemResponse response = toItemResponse(quotationItemRepository.save(item));
        // The lines changed, so the totals are stale by definition.
        totalsCalculator.recalculateFor(quotationId);
        return response;
    }

    /**
     * Prices a gift card at one of the amounts it is actually sold at.
     *
     * Two things differ from legacy, both on purpose.
     *
     * It writes sign_price as well as rate. Legacy's updateItem sets only rate
     * and recomputes with `amount = qty * rate`; ours recomputes from
     * sign_price (applyQty, and QuotationTotalsCalculator with it), so setting
     * rate alone would hold until the next quantity change and then silently
     * reprice the card to zero.
     *
     * And the figure is checked against the amounts configured for the club's
     * country. Legacy takes whatever the browser posts —
     * OrdersItemModel::updateItem only asks that it be numeric and non-empty —
     * so a crafted request can sell a $100 gift card for one colón. The list is
     * three rows and already loaded for the dropdown, so checking it costs a
     * query we were making anyway.
     */
    private void applyPresetAmount(QuotationItem item, BigDecimal amount) {
        String productId = item.getProductId();
        if (!SpecialItems.has(productId, SpecialItems.Trait.PRESET_AMOUNT)) {
            throw new InvalidPresetAmountException(
                    "Product " + productId + " is not sold at preset amounts");
        }

        Integer clubId = item.getQuotation() != null ? item.getQuotation().getStoreId() : null;
        boolean offered = presetAmountService.getForProductAndClub(productId, clubId).stream()
                .anyMatch(preset -> preset.getLocalAmount() != null
                        && preset.getLocalAmount().compareTo(amount) == 0);
        if (!offered) {
            throw new InvalidPresetAmountException(
                    "Amount " + amount.toPlainString() + " is not offered for product "
                    + productId + " at club " + clubId);
        }

        item.setRate(amount);
        item.setSignPrice(amount);
        BigDecimal qty = MapUtils.coalesce(item.getQty(), BigDecimal.ONE);
        item.setAmount(qty.multiply(amount).setScale(4, java.math.RoundingMode.HALF_UP));
    }

    private void applyQty(QuotationItem item, BigDecimal newQty) {
        BigDecimal signPrice = MapUtils.coalesce(item.getSignPrice(), BigDecimal.ZERO);
        BigDecimal taxFactor = item.getTaxes() != null ? MapUtils.coalesce(item.getTaxes().getTaxFactor(), BigDecimal.ZERO) : BigDecimal.ZERO;

        item.setQty(newQty);
        item.setAmount(newQty.multiply(signPrice).setScale(4, java.math.RoundingMode.HALF_UP));
        if (item.getTaxes() != null) {
            item.getTaxes().setTaxAmount(newQty.multiply(taxFactor).setScale(4, java.math.RoundingMode.HALF_UP));
        }

        if (item.getProduct() != null) {
            BigDecimal plVal = MapUtils.coalesce(item.getProduct().getPl(), BigDecimal.ONE);
            BigDecimal weightPerUnit = MapUtils.coalesce(item.getProduct().getWeightPerUnit(), BigDecimal.ZERO);
            item.getProduct().setWeightResult(newQty.multiply(weightPerUnit).setScale(4, java.math.RoundingMode.HALF_UP));
            item.getProduct().setPalletQuantity(plVal.compareTo(BigDecimal.ZERO) != 0
                    ? newQty.divide(plVal, 4, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        }

        log.info("[QuotationService] updateItem id={} qty={}", item.getId(), newQty);
    }

    /**
     * Exemption percentage, with legacy's formula:
     *
     *   excent_amount = ROUND((excent_porcentaje / tax_porcentaje) * tax_amount, 2)
     *
     * (OrdersItemModel::updateItem). Two guards legacy gets for free from
     * MySQL and Java does not:
     *
     *  - tax_porcentaje = 0 makes MySQL return NULL for that division; in Java
     *    it would throw, so a zero rate means zero exemption.
     *  - the percentage is capped at the line's own tax percentage. Legacy only
     *    enforces that in the browser (data-max-tax on #item_exemp), which any
     *    caller can bypass, so it is enforced here too.
     */
    private void applyExemption(QuotationItem item, BigDecimal requested) {
        if (item.getTaxes() == null) return;

        BigDecimal taxPct = MapUtils.coalesce(item.getTaxes().getTaxPercentage(), BigDecimal.ZERO);
        BigDecimal taxAmount = MapUtils.coalesce(item.getTaxes().getTaxAmount(), BigDecimal.ZERO);

        // No tax on the line means there is nothing to exempt, so the percentage
        // is forced to zero rather than stored. Keeping a percentage against a
        // zero rate leaves a figure that reads as meaningful and is not: it
        // can never produce an exempt amount.
        BigDecimal pct = requested.max(BigDecimal.ZERO);
        if (taxPct.compareTo(BigDecimal.ZERO) <= 0) {
            pct = BigDecimal.ZERO;
        } else if (pct.compareTo(taxPct) > 0) {
            pct = taxPct;
        }

        BigDecimal exemptionAmount = taxPct.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : pct.divide(taxPct, 10, java.math.RoundingMode.HALF_UP)
                     .multiply(taxAmount)
                     .setScale(2, java.math.RoundingMode.HALF_UP);

        item.getTaxes().setExemptionPercentage(pct);
        item.getTaxes().setExemptionAmount(exemptionAmount);

        log.info("[QuotationService] updateItem id={} exemption={}% amount={}", item.getId(), pct, exemptionAmount);
    }

    // ── Delete item ───────────────────────────────────────────────────────

    @Transactional
    public void deleteItem(Long quotationId, Long itemId) {
        findOrThrow(quotationId);
        QuotationItem item = quotationItemRepository.findById(itemId)
                .orElseThrow(() -> new QuotationNotFoundException(itemId));
        if (!item.getQuotation().getId().equals(quotationId)) {
            throw new IllegalArgumentException("Item " + itemId + " does not belong to quotation " + quotationId);
        }
        quotationItemRepository.deleteById(itemId);
        log.info("[QuotationService] item deleted id={} quotation_id={}", itemId, quotationId);
        // The lines changed, so the totals are stale by definition.
        totalsCalculator.recalculateFor(quotationId);
    }

    // ── Cancel quotation (pending only) ───────────────────────────────────

    public List<Map<String, Object>> getCancelReasons() {
        return quotationCancelRepository.findReasons();
    }

    @Transactional
    public void cancelQuotation(Long id, Integer reasonId) {
        Quotation quotation = findOrThrow(id);
        if (quotation.getStatusId() != 1) {
            throw new QuotationAlreadySubmittedException(id, quotation.getStatusId());
        }
        if (reasonId == null) {
            throw new IllegalArgumentException("reasonId is required to cancel a quotation");
        }
        quotationCancelRepository.cancel(id, reasonId);
        log.info("[QuotationService] quotation cancelled id={} reasonId={}", id, reasonId);
    }

    // ── Header comment ────────────────────────────────────────────────────

    /**
     * Saves the quotation header note — legacy's orders/savecomment with
     * item == 0. The per-line note goes through updateItem's `comment` key,
     * which is the same endpoint's item != 0 branch.
     *
     * No status guard: legacy lets the note be edited on any quotation, and it
     * changes nothing that is invoiced or sent to OMS.
     */
    @Transactional
    public void updateComment(Long id, String comment) {
        Quotation quotation = findOrThrow(id);
        quotation.setComments(comment == null || comment.isBlank() ? null : comment);
        quotationRepository.save(quotation);
        log.info("[QuotationService] header comment saved id={} length={}", id,
                comment == null ? 0 : comment.length());
    }

    // ── Season ────────────────────────────────────────────────────────────

    /**
     * Tags the quotation with a campaign, or clears it — legacy's
     * orders/temporadaupdate (Orders.php:639), which is a bare
     * `UPDATE orders SET temporada_id = ?` with no validation of any kind.
     *
     * Here the season has to be active and assigned to the quotation's own
     * club. The screen filters the list already, so this only catches a stale
     * page or a request made by hand — but without it the column would accept
     * any integer, including a finished campaign or another country's.
     *
     * A null clears the tag. Legacy's select posts 0 for its placeholder and
     * stores that; we keep one spelling of nothing.
     */
    @Transactional
    public QuotationResponse updateSeason(Long id, Integer seasonId) {
        Quotation quotation = findOrThrow(id);

        if (seasonId != null && seasonId != 0
                && !seasonService.isAvailableForClub(seasonId, quotation.getStoreId())) {
            throw new InvalidSeasonException(
                    "Season " + seasonId + " is not available for club " + quotation.getStoreId());
        }

        quotation.setSeasonId(seasonId == null || seasonId == 0 ? null : seasonId);
        log.info("[QuotationService] updateSeason id={} seasonId={}", id, quotation.getSeasonId());
        return toResponse(quotationRepository.save(quotation));
    }

    // ── Extend the expiry ─────────────────────────────────────────────────

    /**
     * Pushes the expiry out by 21 days — legacy's Orders::extenderfecha
     * (application/controllers/Orders.php:656-679).
     *
     * It adds to the date already on the quotation, not to today, so a second
     * press gives 42 days and a quotation that lapsed a month ago is extended
     * from the day it lapsed rather than revived from now. Legacy's
     * DATE_ADD(dexpired, INTERVAL 21 DAY) behaves the same way, and like it
     * there is no cap, no status check and no audit trail — the button is
     * simply not offered to the base role.
     *
     * The one place we cannot follow it is a quotation with no expiry at all:
     * MySQL's DATE_ADD(NULL, ...) is NULL, so legacy silently blanks the field
     * and the screen then shows nothing. Here it becomes 21 days from today,
     * which is what a quotation created without an explicit expiry already
     * gets.
     */
    @Transactional
    public QuotationResponse extendExpiry(Long id) {
        Quotation quotation = findOrThrow(id);

        LocalDate current  = quotation.getExpiryDate();
        LocalDate extended = (current != null ? current : LocalDate.now()).plusDays(EXPIRY_DAYS);
        quotation.setExpiryDate(extended);

        log.info("[QuotationService] extendExpiry id={} {} -> {}", id, current, extended);
        return toResponse(quotationRepository.save(quotation));
    }

    // ── Get items ─────────────────────────────────────────────────────────

    public List<QuotationItemResponse> getItems(Long quotationId) {
        findOrThrow(quotationId);
        return quotationItemRepository
                .findByQuotation_IdOrderByProductIdAsc(quotationId)
                .stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
    }

    // ── Close ─────────────────────────────────────────────────────────────

    @Transactional
    public QuotationResponse closeQuotation(Long id, CloseQuotationRequest req) {
        log.info("[QuotationService] closeQuotation id={} quoteTypeId={} paymentMethodId={}", id, req.getQuoteTypeId(), req.getPaymentMethodId());

        Quotation quotation = findOrThrow(id);
        if (quotation.getStatusId() != 1) {
            throw new QuotationAlreadySubmittedException(id, quotation.getStatusId());
        }

        quotation.setStatusId(3);

        QuotationPayment payment = ensurePayment(quotation);
        payment.setQuoteTypeId(req.getQuoteTypeId());
        payment.setPaymentNumber(req.getPaymentNumber());
        payment.setPaymentMethodId(req.getPaymentMethodId());

        return toResponse(quotationRepository.save(quotation));
    }

    // ── Send to OMS ───────────────────────────────────────────────────────

    @Transactional
    public String sendToOms(Long id, SendToOmsRequest req) {
        log.info("[QuotationService] sendToOms id={}", id);

        Quotation quotation = findOrThrow(id);
        if (quotation.getStatusId() != 3) {
            throw new IllegalStateException(
                    "Quotation " + id + " must be closed (status=3). Current: " + quotation.getStatusId());
        }

        List<QuotationItem> items = quotationItemRepository.findByQuotation_IdOrderByProductIdAsc(id);

        String membership = quotation.getCustomer() != null ? quotation.getCustomer().getCustomerMembership() : null;
        Map<String, Object> context = omsService.buildOmsContext(id, quotation.getStoreId(), membership, quotation.getUserId());
        Map<String, Object> payload = omsPayloadBuilder.build(quotation, items, context, req.getDeliveryWindows());

        String token = getOmsToken();

        Map<String, Object> club = castMap(context.get("club"));
        String countryIso2 = club != null ? str(club.get("pais_iso2"), "CR") : "CR";

        long omsRequestedAt = System.currentTimeMillis();
        String omsResponse = omsService.submitOrder(payload, countryIso2, token);
        long omsElapsedMs  = System.currentTimeMillis() - omsRequestedAt;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = objectMapper.readValue(omsResponse, Map.class);
            if (resp.containsKey("orderId")) {
                String orderId = resp.get("orderId").toString();
                ensurePayment(quotation).setQuoteNo(orderId);
                quotationRepository.save(quotation);
                log.info("[OMS_SUBMIT] status=OK quoteId={} membership={} storeId={} country={} omsOrderId={} elapsedMs={}",
                        id, membership, quotation.getStoreId(), countryIso2, orderId, omsElapsedMs);
            } else {
                log.warn("[OMS_SUBMIT] status=REJECTED quoteId={} membership={} storeId={} country={} elapsedMs={} response={}",
                        id, membership, quotation.getStoreId(), countryIso2, omsElapsedMs, omsResponse);
            }
        } catch (Exception e) {
            log.error("[OMS_SUBMIT] status=ERROR quoteId={} membership={} storeId={} country={} elapsedMs={} error={}",
                    id, membership, quotation.getStoreId(), countryIso2, omsElapsedMs, e.getMessage());
        }

        return omsResponse;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Quotation findOrThrow(Long id) {
        return quotationRepository.findById(id).orElseThrow(() -> new QuotationNotFoundException(id));
    }

    private QuotationPayment ensurePayment(Quotation quotation) {
        if (quotation.getPayment() == null) {
            QuotationPayment payment = QuotationPayment.builder()
                    .quotation(quotation)
                    .paidStatus(1)
                    .serviceId(0)
                    .paymentMethodId("0")
                    .build();
            quotation.setPayment(payment);
        }
        return quotation.getPayment();
    }

    private LocalDate parseExpiry(String expiryDate) {
        if (expiryDate == null || expiryDate.isBlank()) return LocalDate.now().plusDays(EXPIRY_DAYS);
        try {
            return LocalDate.parse(expiryDate);
        } catch (DateTimeParseException e) {
            log.warn("[QuotationService] expiryDate inválido '{}', usando +{} días", expiryDate, EXPIRY_DAYS);
            return LocalDate.now().plusDays(EXPIRY_DAYS);
        }
    }

    private String str(Object val, String def) {
        if (val == null) return def;
        String s = val.toString().trim();
        return s.isEmpty() ? def : s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object val) {
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    // ── Mappers ───────────────────────────────────────────────────────────

    private QuotationResponse toResponse(Quotation q) {
        QuotationCustomer c = q.getCustomer();
        QuotationTotals   t = q.getTotals();
        QuotationPayment  p = q.getPayment();

        QuotationResponse.QuotationResponseBuilder b = QuotationResponse.builder()
                .id(q.getId())
                .storeId(q.getStoreId())
                .userId(q.getUserId())
                .statusId(q.getStatusId())
                .dateTime(q.getDateTime())
                .expiryDate(q.getExpiryDate())
                .comments(q.getComments())
                .seasonId(q.getSeasonId());

        if (c != null) {
            b.customerName(c.getCustomerName())
             .customerMembership(c.getCustomerMembership())
             .customerBusiness(c.getCustomerBusiness());
        }

        if (t != null) {
            b.taxRate(t.getTaxRate())
             .applyTaxes(t.getApplyTaxes())
             .excent(t.getExcent())
             .grossAmount(t.getGrossAmount())
             .netAmount(t.getNetAmount())
             .discount(t.getDiscount())
             .vatChargeRate(t.getVatChargeRate())
             .vatCharge(t.getVatCharge())
             .serviceChargeRate(t.getServiceChargeRate())
             .serviceCharge(t.getServiceCharge())
             .deliveryAmount(t.getDeliveryAmount());
        }

        if (p != null) {
            b.paidStatus(p.getPaidStatus())
             .quoteTypeId(p.getQuoteTypeId())
             .paymentNumber(p.getPaymentNumber())
             .paymentMethodId(p.getPaymentMethodId())
             .serviceId(p.getServiceId())
             .quoteNo(p.getQuoteNo());
        }

        return b.build();
    }

    private QuotationItemResponse toItemResponse(QuotationItem i) {
        QuotationItemTaxes   tx = i.getTaxes();
        QuotationItemProduct pr = i.getProduct();

        QuotationItemResponse.QuotationItemResponseBuilder b = QuotationItemResponse.builder()
                .id(i.getId())
                .quotationId(i.getQuotation().getId())
                .productId(i.getProductId())
                .qty(i.getQty())
                .rate(i.getRate())
                .signPrice(i.getSignPrice())
                .amount(i.getAmount())
                .comment(i.getComment())
                .includeImage(i.getIncludeImage())
                .priceVariation(i.getPriceVariation());

        if (tx != null) {
            b.taxPercentage(tx.getTaxPercentage())
             .taxFactor(tx.getTaxFactor())
             .taxAmount(tx.getTaxAmount())
             .taxIco(tx.getTaxIco())
             .exemptionPercentage(tx.getExemptionPercentage())
             .exemptionAmount(tx.getExemptionAmount());
        }

        if (pr != null) {
            b.description(pr.getDescription())
             .packSize(pr.getPackSize())
             .pl(pr.getPl())
             .weightPerUnit(pr.getWeightPerUnit())
             .weightResult(pr.getWeightResult())
             .palletQuantity(pr.getPalletQuantity())
             .onhand(pr.getOnhand())
             .soldByWeight(pr.getSoldByWeight())
             .recipe(pr.getRecipe())
             .storageType(pr.getStorageType())
             .picture1(pr.getPicture1())
             .department(pr.getDepartment())
             .category(pr.getCategory());
        }

        return b.build();
    }

    // ── OMS status ───────────────────────────────────────────────────────

    public Object getOmsStatus(Long id) {
        Quotation quotation = findOrThrow(id);
        if (quotation.getPayment() == null || quotation.getPayment().getQuoteNo() == null) {
            throw new IllegalStateException("Quotation " + id + " has no OMS order number yet.");
        }
        String quoteNo = quotation.getPayment().getQuoteNo();
        log.info("[QuotationService] getOmsStatus id={} quoteNo={}", id, quoteNo);
        String response = omsService.getOrderStatusHistory(quoteNo, getOmsToken());
        try {
            return objectMapper.readValue(response, Object.class);
        } catch (Exception e) {
            return response;
        }
    }

    // ── OAuth2 token ──────────────────────────────────────────────────────

    private String getOmsToken() {
        String url = idpBaseUrl + "/auth/realms/PriceSmart/protocol/openid-connect/token";
        log.info("[QuotationService] getOmsToken url={}", url);
        try {
            String body = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = objectMapper.readValue(response, Map.class);
            String token = resp.getOrDefault("access_token", "").toString();
            log.info("[QuotationService] token obtained length={}", token.length());
            return token;

        } catch (Exception e) {
            log.error("[QuotationService] Error getting OMS token: {}", e.getMessage());
            throw new RuntimeException("Error obteniendo token OMS: " + e.getMessage());
        }
    }
}
