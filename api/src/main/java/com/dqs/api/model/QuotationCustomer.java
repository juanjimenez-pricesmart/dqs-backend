package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "quotation_customers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false, unique = true)
    private Quotation quotation;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_membership", length = 50)
    private String customerMembership;

    @Column(name = "customer_business", length = 200)
    private String customerBusiness;
}
