package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A payment method, defined once. Was a repeated name in `orders_pago`.
 *
 * The legacy table carried the name on every country row, which is how two
 * spellings of the same method came to exist. Only what genuinely varies by
 * country lives in {@link CountryPaymentMethod}.
 */
@Entity
@Table(name = "payment_method_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentMethodType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
