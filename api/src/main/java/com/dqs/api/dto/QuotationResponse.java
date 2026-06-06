package com.dqs.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class QuotationResponse {

    // header
    private Long id;
    private Integer storeId;
    private Integer userId;
    private Integer statusId;
    private LocalDateTime dateTime;
    private LocalDate dexpired;

    // customer
    private String customerName;
    private String customerMembership;
    private String customerBusiness;

    // totals
    private BigDecimal taxRate;
    private Integer aplicarImpuestos;
    private Integer excent;
    private BigDecimal grossAmount;
    private BigDecimal netAmount;
    private BigDecimal discount;
    private BigDecimal vatChargeRate;
    private BigDecimal vatCharge;
    private BigDecimal serviceChargeRate;
    private BigDecimal serviceCharge;
    private BigDecimal deliveryAmount;

    // payment (populated after close)
    private Integer paidStatus;
    private Integer quoteTypeId;
    private String paymentNumber;
    private String paymentMethodId;
    private Integer serviceId;
    private String quoteNo;
}
