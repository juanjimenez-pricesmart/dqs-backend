package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_item_taxes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationItemTaxes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false, unique = true)
    private QuotationItem item;

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
}
