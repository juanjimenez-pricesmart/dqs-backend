package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/** Fiscal neighborhood catalog, keyed by (code, zoneCode, cityCode). See {@link City}. */
@Entity
@Table(name = "neighborhoods",
       uniqueConstraints = @UniqueConstraint(name = "uq_neighborhoods",
                                             columnNames = {"code", "zone_code", "city_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Neighborhood {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "zone_code", nullable = false, length = 20)
    private String zoneCode;

    @Column(name = "city_code", nullable = false, length = 20)
    private String cityCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
