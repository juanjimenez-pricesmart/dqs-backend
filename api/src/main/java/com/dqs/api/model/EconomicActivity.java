package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Fiscal economic-activity catalog. See {@link City}.
 *
 * `code` carries a unique index of its own, narrower than the (code, country)
 * pair the other catalogs use, so the upsert matches on the code alone.
 */
@Entity
@Table(name = "economic_activities")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EconomicActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    /** The activity's description. Column is `value`, as the API exposes it. */
    @Column(name = "value", nullable = false, length = 255)
    private String value;

    @Column(name = "country", length = 10)
    private String country;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
