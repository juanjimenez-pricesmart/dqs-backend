// service/QuotationService.java
package com.dqs.api.service;

import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.CloseQuotationRequest;
import com.dqs.api.dto.QuotationItemRequest;
import com.dqs.api.dto.QuotationItemResponse;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.model.QuotationItem;
import com.dqs.api.repository.QuotationItemRepository;
import com.dqs.api.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;

    @Transactional
    public QuotationResponse createQuotation(CreateQuotationRequest request) {

        log.info("[QuotationService] createQuotation store_id={} user_id={}",
                request.getStoreId(), request.getUserId());

        LocalDate dexpired = parseExpiry(request.getDexpired());

        Quotation quotation = Quotation.builder()
                .customerName(request.getCustomerName())
                .customerMembership(request.getCustomerMembership())
                .customerBusiness(request.getCustomerBusiness())
                .storeId(request.getStoreId())
                .userId(request.getUserId())
                .taxRate(request.getTaxRate())
                .aplicarImpuestos(request.getAplicarImpuestos())
                .grossAmount(request.getGrossAmount())
                .netAmount(request.getNetAmount())
                .discount(request.getDiscount())
                .vatChargeRate(request.getVatChargeRate())
                .vatCharge(request.getVatCharge())
                .serviceChargeRate(request.getServiceChargeRate())
                .serviceCharge(request.getServiceCharge())
                .deliveryAmount(java.math.BigDecimal.ZERO)
                .excent(0)
                .statusId(1)       // borrador
                .paidStatus(1)
                .paymentMethodId("0")
                .serviceId(0)
                .dexpired(dexpired)
                .build();

        Quotation saved = quotationRepository.save(quotation);

        log.info("[QuotationService] quotation created id={}", saved.getId());

        return toResponse(saved);
    }

    private LocalDate parseExpiry(String dexpired) {
        if (dexpired == null || dexpired.isBlank()) {
            return LocalDate.now().plusDays(21);
        }
        try {
            return LocalDate.parse(dexpired);
        } catch (DateTimeParseException e) {
            log.warn("[QuotationService] dexpired inválido '{}', usando +21 días", dexpired);
            return LocalDate.now().plusDays(21);
        }
    }

    private QuotationResponse toResponse(Quotation q) {
        return QuotationResponse.builder()
                .id(q.getId())
                .customerName(q.getCustomerName())
                .customerMembership(q.getCustomerMembership())
                .customerBusiness(q.getCustomerBusiness())
                .storeId(q.getStoreId())
                .userId(q.getUserId())
                .taxRate(q.getTaxRate())
                .aplicarImpuestos(q.getAplicarImpuestos())
                .grossAmount(q.getGrossAmount())
                .netAmount(q.getNetAmount())
                .statusId(q.getStatusId())
                .dateTime(q.getDateTime())
                .dexpired(q.getDexpired())
                .build();
    }

    @Transactional
    public QuotationResponse submitQuotation(Long id, SubmitQuotationRequest request) {

        log.info("[QuotationService] submitQuotation id={} submitted_by={}",
                id, request.getSubmittedBy());

        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new QuotationNotFoundException(id));

        if (quotation.getStatusId() != 1) {
            throw new QuotationAlreadySubmittedException(id, quotation.getStatusId());
        }

        quotation.setStatusId(2);  // 2 = enviada
        quotation.setPaymentMethodId(String.valueOf(request.getPaymentMethodId() != null ? request.getPaymentMethodId() : 0));

        Quotation saved = quotationRepository.save(quotation);

        log.info("[QuotationService] quotation submitted id={} status=2", saved.getId());

        return toResponse(saved);
    }

    // GET por id — ahora lo implementamos también
    public QuotationResponse getById(Long id) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new QuotationNotFoundException(id));
        return toResponse(quotation);
    }

    // ── Guardar item ──────────────────────────────────────────────────────

    @Transactional
    public QuotationItemResponse saveItem(Long quotationId, QuotationItemRequest request) {

        log.info("[QuotationService] saveItem quotation_id={} product_id={}",
                quotationId, request.getProductId());

        // Verificar que la cotización existe
        quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        // Si el item ya existe, actualizar qty; si no, insertar
        QuotationItem item = quotationItemRepository
                .findByQuotationIdAndProductId(quotationId, request.getProductId())
                .orElse(QuotationItem.builder()
                        .quotationId(quotationId)
                        .productId(request.getProductId())
                        .excentPorcentaje(BigDecimal.ZERO)
                        .excentAmount(BigDecimal.ZERO)
                        .icomments("")
                        .includepic(0)
                        .variacion(0)
                        .build());

        // Calcular amount = qty × signPrice
        BigDecimal qty       = request.getQty()       != null ? request.getQty()       : BigDecimal.ZERO;
        BigDecimal signPrice = request.getSignPrice()  != null ? request.getSignPrice() : BigDecimal.ZERO;
        BigDecimal taxFactor = request.getTaxFactor()  != null ? request.getTaxFactor() : BigDecimal.ZERO;
        BigDecimal pl        = request.getPl()         != null ? request.getPl()        : BigDecimal.ONE;
        BigDecimal weightEa  = request.getWeightEa()   != null ? request.getWeightEa()  : BigDecimal.ZERO;

        BigDecimal amount       = qty.multiply(signPrice).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal taxAmount    = qty.multiply(taxFactor).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal weightResult = qty.multiply(weightEa).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal palletxqty   = pl.compareTo(BigDecimal.ZERO) != 0
                ? qty.divide(pl, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Mapear campos
        item.setDescription(request.getDescription());
        item.setQty(qty);
        item.setRate(request.getRate());
        item.setSignPrice(signPrice);
        item.setAmount(amount);
        item.setTaxPorcentaje(request.getTaxPorcentaje());
        item.setTaxFactor(taxFactor);
        item.setTaxAmount(taxAmount);
        item.setTaxIco(request.getTaxIco() != null ? request.getTaxIco() : BigDecimal.ZERO);
        item.setCuEa(request.getCuEa());
        item.setPl(pl);
        item.setWeightEa(weightEa);
        item.setWeightResult(weightResult);
        item.setPalletxqty(palletxqty);
        item.setOnhand(request.getOnhand());
        item.setSoldByWeight(request.getSoldByWeight());
        item.setRecipe(request.getRecipe());
        item.setStorageType(request.getStorageType());
        item.setPicture1(request.getPicture1());
        item.setDepartment(request.getDepartment());
        item.setCategory(request.getCategory());

        QuotationItem saved = quotationItemRepository.save(item);

        log.info("[QuotationService] item saved id={} quotation_id={} product_id={}",
                saved.getId(), quotationId, saved.getProductId());

        return toItemResponse(saved);
    }

    // ── Listar items ──────────────────────────────────────────────────────

    public List<QuotationItemResponse> getItems(Long quotationId) {
        quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        return quotationItemRepository
                .findByQuotationIdOrderByProductIdAsc(quotationId)
                .stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public QuotationResponse closeQuotation(Long id, CloseQuotationRequest request) {

        log.info("[QuotationService] closeQuotation id={} quoteTypeId={} paymentMethodId={}",
                id, request.getQuoteTypeId(), request.getPaymentMethodId());

        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new QuotationNotFoundException(id));

        if (quotation.getStatusId() != 1) {
            throw new QuotationAlreadySubmittedException(id, quotation.getStatusId());
        }

        quotation.setStatusId(3);  // 3 = cerrada/venta
        quotation.setQuoteTypeId(request.getQuoteTypeId());
        quotation.setPaymentNumber(request.getPaymentNumber());
        quotation.setPaymentMethodId(request.getPaymentMethodId());

        Quotation saved = quotationRepository.save(quotation);

        log.info("[QuotationService] quotation closed id={} status=3", saved.getId());

        return toResponse(saved);
    }

    // ── Mapper ────────────────────────────────────────────────────────────

    private QuotationItemResponse toItemResponse(QuotationItem i) {
        return QuotationItemResponse.builder()
                .id(i.getId())
                .quotationId(i.getQuotationId())
                .productId(i.getProductId())
                .description(i.getDescription())
                .qty(i.getQty())
                .rate(i.getRate())
                .signPrice(i.getSignPrice())
                .amount(i.getAmount())
                .taxPorcentaje(i.getTaxPorcentaje())
                .taxFactor(i.getTaxFactor())
                .taxAmount(i.getTaxAmount())
                .taxIco(i.getTaxIco())
                .cuEa(i.getCuEa())
                .pl(i.getPl())
                .weightEa(i.getWeightEa())
                .weightResult(i.getWeightResult())
                .palletxqty(i.getPalletxqty())
                .onhand(i.getOnhand())
                .soldByWeight(i.getSoldByWeight())
                .recipe(i.getRecipe())
                .storageType(i.getStorageType())
                .picture1(i.getPicture1())
                .department(i.getDepartment())
                .category(i.getCategory())
                .build();
    }
}