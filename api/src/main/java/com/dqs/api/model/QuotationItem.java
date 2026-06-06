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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(name = "product_id", nullable = false, length = 20)
    private String productId;

    @Column(name = "qty", precision = 10, scale = 2)
    private BigDecimal qty;

    @Column(name = "rate", precision = 15, scale = 4)
    private BigDecimal rate;

    @Column(name = "sign_price", precision = 15, scale = 4)
    private BigDecimal signPrice;

    @Column(name = "amount", precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(name = "icomments", length = 500)
    private String icomments;

    @Column(name = "includepic", columnDefinition = "TINYINT")
    private Integer includepic;

    @Column(name = "variacion", columnDefinition = "TINYINT")
    private Integer variacion;

    @OneToOne(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    private QuotationItemTaxes taxes;

    @OneToOne(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    private QuotationItemProduct product;
}
