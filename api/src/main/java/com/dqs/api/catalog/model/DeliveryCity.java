package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A city a delivery can be sent to. Was `ps_delivery_ciudades`.
 *
 * The legacy idco is the id, assigned by the import rather than generated:
 * quotation_delivery stores the chosen city, so a surrogate key would orphan
 * deliveries already saved and would change meaning when the catalog flag
 * flips — the same reasoning as {@link FiscalDocumentType}'s felid.
 */
@Entity
@Table(name = "delivery_cities")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryCity {

    @Id
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
