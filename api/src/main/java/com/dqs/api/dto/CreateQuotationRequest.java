// dto/CreateQuotationRequest.java
package com.dqs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateQuotationRequest {

    @NotBlank(message = "customer_name es requerido")
    private String customerName;

    private String customerMembresia;
    private String customerBusiness;

    @NotNull(message = "store_id es requerido")
    private Integer storeId;

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