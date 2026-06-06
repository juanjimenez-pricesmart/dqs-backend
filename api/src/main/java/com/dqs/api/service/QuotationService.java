package com.dqs.api.service;

import com.dqs.api.dto.*;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
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
    private final OmsService omsService;
    private final OmsPayloadBuilder omsPayloadBuilder;
    private final ObjectMapper objectMapper;

    @Value("${idp.base-url}")
    private String idpBaseUrl;

    @Value("${idp.client-id}")
    private String clientId;

    @Value("${idp.client-secret}")
    private String clientSecret;

    @Value("${dqs.base-url}")
    private String dqsBaseUrl;

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public QuotationResponse createQuotation(CreateQuotationRequest req) {
        log.info("[QuotationService] createQuotation store_id={} user_id={}", req.getStoreId(), req.getUserId());

        Quotation quotation = Quotation.builder()
                .storeId(req.getStoreId())
                .userId(req.getUserId())
                .statusId(1)
                .dexpired(parseExpiry(req.getDexpired()))
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
                .aplicarImpuestos(req.getAplicarImpuestos())
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

        BigDecimal qty       = coalesce(req.getQty(),       BigDecimal.ZERO);
        BigDecimal signPrice = coalesce(req.getSignPrice(),  BigDecimal.ZERO);
        BigDecimal taxFactor = coalesce(req.getTaxFactor(),  BigDecimal.ZERO);
        BigDecimal pl        = coalesce(req.getPl(),         BigDecimal.ONE);
        BigDecimal weightEa  = coalesce(req.getWeightEa(),   BigDecimal.ZERO);
        BigDecimal taxIco    = coalesce(req.getTaxIco(),     BigDecimal.ZERO);

        BigDecimal amount       = qty.multiply(signPrice).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal taxAmount    = qty.multiply(taxFactor).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal weightResult = qty.multiply(weightEa).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal palletxqty   = pl.compareTo(BigDecimal.ZERO) != 0
                ? qty.divide(pl, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        QuotationItem item = quotationItemRepository
                .findByQuotation_IdAndProductId(quotationId, req.getProductId())
                .orElseGet(() -> {
                    QuotationItem newItem = QuotationItem.builder()
                            .quotation(quotation)
                            .productId(req.getProductId())
                            .icomments("")
                            .includepic(0)
                            .variacion(0)
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
        taxes.setTaxPorcentaje(req.getTaxPorcentaje());
        taxes.setTaxFactor(taxFactor);
        taxes.setTaxAmount(taxAmount);
        taxes.setTaxIco(taxIco);
        taxes.setExcentPorcentaje(BigDecimal.ZERO);
        taxes.setExcentAmount(BigDecimal.ZERO);

        QuotationItemProduct product = item.getProduct();
        product.setDescription(req.getDescription());
        product.setCuEa(req.getCuEa());
        product.setPl(pl);
        product.setWeightEa(weightEa);
        product.setWeightResult(weightResult);
        product.setPalletxqty(palletxqty);
        product.setOnhand(req.getOnhand());
        product.setSoldByWeight(req.getSoldByWeight());
        product.setRecipe(req.getRecipe());
        product.setStorageType(req.getStorageType());
        product.setPicture1(req.getPicture1());
        product.setDepartment(req.getDepartment());
        product.setCategory(req.getCategory());

        QuotationItem saved = quotationItemRepository.save(item);
        log.info("[QuotationService] item saved id={} quotation_id={} product_id={}", saved.getId(), quotationId, saved.getProductId());
        return toItemResponse(saved);
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
        Map<String, Object> context = omsService.getQuotationContext(id, quotation.getStoreId(), membership, quotation.getUserId());
        Map<String, Object> payload = omsPayloadBuilder.build(quotation, items, context, req.getVentanas());

        String token = getOmsToken();

        Map<String, Object> club = castMap(context.get("club"));
        String paisIso2 = club != null ? str(club.get("pais_iso2"), "CR") : "CR";

        String omsResponse = omsService.sendPayload(payload, paisIso2, token);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = objectMapper.readValue(omsResponse, Map.class);
            if (resp.containsKey("orderId")) {
                String orderId = resp.get("orderId").toString();
                quotation.setStatusId(2);
                ensurePayment(quotation).setQuoteNo(orderId);
                quotationRepository.save(quotation);
                log.info("[QuotationService] OMS OK orderId={} quotation_id={}", orderId, id);
            } else {
                log.warn("[QuotationService] OMS rejected quotation_id={} response={}", id, omsResponse);
            }
        } catch (Exception e) {
            log.error("[QuotationService] Error parsing OMS response: {}", e.getMessage());
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

    private LocalDate parseExpiry(String dexpired) {
        if (dexpired == null || dexpired.isBlank()) return LocalDate.now().plusDays(21);
        try {
            return LocalDate.parse(dexpired);
        } catch (DateTimeParseException e) {
            log.warn("[QuotationService] dexpired inválido '{}', usando +21 días", dexpired);
            return LocalDate.now().plusDays(21);
        }
    }

    private BigDecimal coalesce(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
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
                .dexpired(q.getDexpired());

        if (c != null) {
            b.customerName(c.getCustomerName())
             .customerMembership(c.getCustomerMembership())
             .customerBusiness(c.getCustomerBusiness());
        }

        if (t != null) {
            b.taxRate(t.getTaxRate())
             .aplicarImpuestos(t.getAplicarImpuestos())
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
                .icomments(i.getIcomments())
                .includepic(i.getIncludepic())
                .variacion(i.getVariacion());

        if (tx != null) {
            b.taxPorcentaje(tx.getTaxPorcentaje())
             .taxFactor(tx.getTaxFactor())
             .taxAmount(tx.getTaxAmount())
             .taxIco(tx.getTaxIco())
             .excentPorcentaje(tx.getExcentPorcentaje())
             .excentAmount(tx.getExcentAmount());
        }

        if (pr != null) {
            b.description(pr.getDescription())
             .cuEa(pr.getCuEa())
             .pl(pr.getPl())
             .weightEa(pr.getWeightEa())
             .weightResult(pr.getWeightResult())
             .palletxqty(pr.getPalletxqty())
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
