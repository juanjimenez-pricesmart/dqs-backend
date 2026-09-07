package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Availability and tender mapping of a payment method in one country.
 *
 * `tenderKey` is why this table exists rather than a flag on the type: it feeds
 * the OMS payload and genuinely varies — cash alone has five different keys
 * across the region.
 */
@Entity
@Table(name = "country_payment_methods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CountryPaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "method_type_id", nullable = false)
    private PaymentMethodType methodType;

    @Column(name = "tender_key", nullable = false)
    private Integer tenderKey;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
