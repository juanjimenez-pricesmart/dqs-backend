package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Fiscal city catalog, keyed by (code, country).
 *
 * Rows arrive from GoSocket rather than from a seed: FiscalService inserts one
 * the first time a code is seen and refreshes the name afterwards.
 */
@Entity
@Table(name = "cities",
       uniqueConstraints = @UniqueConstraint(name = "uq_cities", columnNames = {"code", "country"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "country", nullable = false, length = 10)
    private String country;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
