package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_item_product")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationItemProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false, unique = true)
    private QuotationItem item;

    @Column(name = "description", length = 500)
    private String description;

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
}
