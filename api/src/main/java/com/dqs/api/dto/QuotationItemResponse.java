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
    private String icomments;
    private Integer includepic;
    private Integer variacion;

    // taxes
    private BigDecimal taxPorcentaje;
    private BigDecimal taxFactor;
    private BigDecimal taxAmount;
    private BigDecimal taxIco;
    private BigDecimal excentPorcentaje;
    private BigDecimal excentAmount;

    // product snapshot
    private String description;
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
