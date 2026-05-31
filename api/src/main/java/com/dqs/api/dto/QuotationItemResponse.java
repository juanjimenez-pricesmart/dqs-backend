// dto/QuotationItemResponse.java
package com.dqs.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class QuotationItemResponse {

    private Long id;
    private Long quotationId;
    private String productId;
    private String description;
    private BigDecimal qty;
    private BigDecimal rate;
    private BigDecimal signPrice;
    private BigDecimal amount;
    private BigDecimal taxPorcentaje;
    private BigDecimal taxFactor;
    private BigDecimal taxAmount;
    private BigDecimal taxIco;
    private BigDecimal cuEa;
    private BigDecimal pl;
    private BigDecimal weightEa;
    private BigDecimal weightResult;
    private BigDecimal palletxqty;
    private BigDecimal onhand;
    private String soldByWeight;
    private String recipe;
    private String storageType;
    private String picture1;
    private String department;
    private String category;
}