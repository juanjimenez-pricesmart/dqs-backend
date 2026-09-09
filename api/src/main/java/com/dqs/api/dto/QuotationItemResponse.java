package com.dqs.api.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class QuotationItemResponse {

    // core
    private Long id;
    private Long quotationId;
    private String productId;
    private BigDecimal qty;
    private BigDecimal rate;
    private BigDecimal signPrice;
    private BigDecimal amount;
    private String comment;
    private Integer includeImage;
    private Integer priceVariation;

    // taxes
    private BigDecimal taxPercentage;
    private BigDecimal taxFactor;
    private BigDecimal taxAmount;
    private BigDecimal taxIco;
    private BigDecimal exemptionPercentage;
    private BigDecimal exemptionAmount;

    // product snapshot
    private String description;
    private BigDecimal packSize;
    private BigDecimal pl;
    private BigDecimal weightPerUnit;
    private BigDecimal weightResult;
    private BigDecimal palletQuantity;
    private BigDecimal onhand;
    private String soldByWeight;
    private String recipe;
    private String storageType;
    private String picture1;
    private String department;
    private String category;
}
