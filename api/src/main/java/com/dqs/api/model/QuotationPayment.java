package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "quotation_payment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false, unique = true)
    private Quotation quotation;

    @Column(name = "paid_status")
    private Integer paidStatus;

    @Column(name = "quote_type_id")
    private Integer quoteTypeId;

    @Column(name = "payment_number", length = 100)
    private String paymentNumber;

    @Column(name = "payment_method_id", length = 20)
    private String paymentMethodId;

    @Column(name = "service_id")
    private Integer serviceId;

    @Column(name = "quote_no", length = 50)
    private String quoteNo;
}
