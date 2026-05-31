// dto/QuotationItemRequest.java
package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class QuotationItemRequest {

    @NotBlank(message = "productId es requerido")
    @JsonProperty("productId")
    private String productId;

    @JsonProperty("description")
    private String description = "";

    @NotNull(message = "qty es requerido")
    @JsonProperty("qty")
    private BigDecimal qty = BigDecimal.ZERO;

    @JsonProperty("rate")
    private BigDecimal rate = BigDecimal.ZERO;

    @JsonProperty("signPrice")
    private BigDecimal signPrice = BigDecimal.ZERO;

    @JsonProperty("taxPorcentaje")
    private BigDecimal taxPorcentaje = BigDecimal.ZERO;

    @JsonProperty("taxFactor")
    private BigDecimal taxFactor = BigDecimal.ZERO;

    @JsonProperty("taxIco")
    private BigDecimal taxIco = BigDecimal.ZERO;

    @JsonProperty("cuEa")
    private BigDecimal cuEa = BigDecimal.ZERO;

    @JsonProperty("pl")
    private BigDecimal pl = BigDecimal.ZERO;

    @JsonProperty("weightEa")
    private BigDecimal weightEa = BigDecimal.ZERO;

    @JsonProperty("onhand")
    private BigDecimal onhand = BigDecimal.ZERO;

    @JsonProperty("soldByWeight")
    private String soldByWeight = "N";

    @JsonProperty("recipe")
    private String recipe = "N";

    @JsonProperty("storageType")
    private String storageType = "";

    @JsonProperty("picture1")
    private String picture1 = "";

    @JsonProperty("department")
    private String department = "";

    @JsonProperty("category")
    private String category = "";
}