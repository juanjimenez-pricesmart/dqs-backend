// dto/CreateQuotationRequest.java
package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateQuotationRequest {

    @NotBlank(message = "customer_name es requerido")
    @JsonProperty("customerName")
    private String customerName;

    @JsonProperty("customerMembership")
    private String customerMembership;

    @JsonProperty("customerBusiness")
    private String customerBusiness;

    @NotNull(message = "store_id es requerido")
    @JsonProperty("storeId")
    private Integer storeId;

    @NotNull(message = "user_id es requerido")
    @JsonProperty("userId")
    private Integer userId;

    @JsonProperty("taxRate")
    private BigDecimal taxRate = BigDecimal.ZERO;

    @JsonProperty("aplicarImpuestos")
    private Integer aplicarImpuestos = 1;

    @JsonProperty("grossAmount")
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @JsonProperty("netAmount")
    private BigDecimal netAmount = BigDecimal.ZERO;

    @JsonProperty("discount")
    private BigDecimal discount = BigDecimal.ZERO;

    @JsonProperty("vatChargeRate")
    private BigDecimal vatChargeRate = BigDecimal.ZERO;

    @JsonProperty("vatCharge")
    private BigDecimal vatCharge = BigDecimal.ZERO;

    @JsonProperty("serviceChargeRate")
    private BigDecimal serviceChargeRate = BigDecimal.ZERO;

    @JsonProperty("serviceCharge")
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    @JsonProperty("dexpired")
    private String dexpired;
}