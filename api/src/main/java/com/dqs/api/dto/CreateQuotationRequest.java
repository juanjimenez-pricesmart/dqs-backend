// dto/CreateQuotationRequest.java
package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateQuotationRequest {

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("customer_membresia")
    private String customerMembership;

    @JsonProperty("customer_business")
    private String customerBusiness;

    @JsonProperty("store_id")
    @NotNull(message = "store_id es requerido")
    private Integer storeId;

    @JsonProperty("user_id")
    @NotNull(message = "user_id es requerido")
    private Integer userId;

    private BigDecimal taxRate       = BigDecimal.ZERO;
    private Integer aplicarImpuestos = 1;

    private BigDecimal grossAmount       = BigDecimal.ZERO;
    private BigDecimal netAmount         = BigDecimal.ZERO;
    private BigDecimal discount          = BigDecimal.ZERO;
    private BigDecimal vatChargeRate     = BigDecimal.ZERO;
    private BigDecimal vatCharge         = BigDecimal.ZERO;
    private BigDecimal serviceChargeRate = BigDecimal.ZERO;
    private BigDecimal serviceCharge     = BigDecimal.ZERO;

    private String dexpired; // "yyyy-MM-dd" — viene calculado desde DQS
}