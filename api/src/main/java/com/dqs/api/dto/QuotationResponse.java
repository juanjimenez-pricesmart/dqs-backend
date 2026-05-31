// dto/QuotationResponse.java
package com.dqs.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class QuotationResponse {

    private Long id;
    private String customerName;
    private String customerMembership;
    private String customerBusiness;
    private Integer storeId;
    private Integer userId;
    private BigDecimal taxRate;
    private Integer aplicarImpuestos;
    private BigDecimal grossAmount;
    private BigDecimal netAmount;
    private Integer statusId;
    private LocalDateTime dateTime;
    private LocalDate dexpired;
}