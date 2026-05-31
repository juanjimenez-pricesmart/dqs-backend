// model/QuotationItem.java
package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quotation_id", nullable = false)
    private Long quotationId;

    @Column(name = "product_id", nullable = false, length = 20)
    private String productId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "qty", precision = 10, scale = 2)
    private BigDecimal qty;

    @Column(name = "rate", precision = 15, scale = 4)
    private BigDecimal rate;

    @Column(name = "sign_price", precision = 15, scale = 4)
    private BigDecimal signPrice;

    @Column(name = "amount", precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(name = "tax_porcentaje", precision = 10, scale = 4)
    private BigDecimal taxPorcentaje;

    @Column(name = "tax_factor", precision = 15, scale = 4)
    private BigDecimal taxFactor;

    @Column(name = "tax_amount", precision = 15, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "tax_ico", precision = 15, scale = 4)
    private BigDecimal taxIco;

    @Column(name = "excent_porcentaje", precision = 10, scale = 4)
    private BigDecimal excentPorcentaje;

    @Column(name = "excent_amount", precision = 15, scale = 4)
    private BigDecimal excentAmount;

    @Column(name = "cu_ea", precision = 10, scale = 4)
    private BigDecimal cuEa;

    @Column(name = "pl", precision = 10, scale = 4)
    private BigDecimal pl;

    @Column(name = "weight_ea", precision = 10, scale = 4)
    private BigDecimal weightEa;

    @Column(name = "weight_result", precision = 15, scale = 4)
    private BigDecimal weightResult;

    @Column(name = "palletxqty", precision = 10, scale = 4)
    private BigDecimal palletxqty;

    @Column(name = "onhand", precision = 10, scale = 2)
    private BigDecimal onhand;

    @Column(name = "soldbyweight", length = 1)
    private String soldByWeight;

    @Column(name = "recipe", length = 1)
    private String recipe;

    @Column(name = "storagetype", length = 10)
    private String storageType;

    @Column(name = "picture1", length = 500)
    private String picture1;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "icomments", length = 500)
    private String icomments;

    @Column(name = "includepic", columnDefinition = "TINYINT")
    private Integer includepic;

    @Column(name = "variacion", columnDefinition = "TINYINT")
    private Integer variacion;
}