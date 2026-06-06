package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_totals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationTotals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false, unique = true)
    private Quotation quotation;

    @Column(name = "tax_rate", precision = 10, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "aplicar_impuestos", columnDefinition = "TINYINT")
    private Integer aplicarImpuestos;

    @Column(name = "excent", columnDefinition = "TINYINT")
    private Integer excent;

    @Column(name = "gross_amount", precision = 15, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "net_amount", precision = 15, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "discount", precision = 15, scale = 4)
    private BigDecimal discount;

    @Column(name = "vat_charge_rate", precision = 10, scale = 4)
    private BigDecimal vatChargeRate;

    @Column(name = "vat_charge", precision = 15, scale = 4)
    private BigDecimal vatCharge;

    @Column(name = "service_charge_rate", precision = 10, scale = 4)
    private BigDecimal serviceChargeRate;

    @Column(name = "service_charge", precision = 15, scale = 4)
    private BigDecimal serviceCharge;

    @Column(name = "delivery_amount", precision = 15, scale = 4)
    private BigDecimal deliveryAmount;
}
