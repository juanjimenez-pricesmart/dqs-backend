// model/Quotation.java
package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "quotations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Cliente ---
    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_membresia", length = 50)
    private String customerMembresia;

    @Column(name = "customer_business", length = 200)
    private String customerBusiness;

    // --- Tienda / contexto ---
    @Column(name = "store_id", nullable = false)
    private Integer storeId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "tax_rate", precision = 10, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "aplicar_impuestos", columnDefinition = "TINYINT")
    private Integer aplicarImpuestos;

    @Column(name = "excent", columnDefinition = "TINYINT")
    private Integer excent;

    // --- Montos ---
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

    // --- Estado ---
    @Column(name = "status_id")
    private Integer statusId;

    @Column(name = "paid_status")
    private Integer paidStatus;

    @Column(name = "payment_method_id")
    private Integer paymentMethodId;

    @Column(name = "service_id")
    private Integer serviceId;

    // --- Fechas ---
    @CreationTimestamp
    @Column(name = "date_time", updatable = false)
    private LocalDateTime dateTime;

    @Column(name = "dexpired")
    private LocalDate dexpired;
}