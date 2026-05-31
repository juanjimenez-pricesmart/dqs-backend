// service/QuotationService.java
package com.dqs.api.service;

import com.dqs.api.dto.CreateQuotationRequest;
import com.dqs.api.dto.QuotationResponse;
import com.dqs.api.dto.SubmitQuotationRequest;
import com.dqs.api.exception.QuotationAlreadySubmittedException;
import com.dqs.api.exception.QuotationNotFoundException;
import com.dqs.api.model.Quotation;
import com.dqs.api.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationService {

    private final QuotationRepository quotationRepository;

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
                .paymentMethodId(0)
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
        quotation.setPaymentMethodId(request.getPaymentMethodId());

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
}