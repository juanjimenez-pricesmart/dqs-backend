package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/** Fiscal zone catalog, keyed by (code, cityCode). See {@link City}. */
@Entity
@Table(name = "zones",
       uniqueConstraints = @UniqueConstraint(name = "uq_zones", columnNames = {"code", "city_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "city_code", nullable = false, length = 20)
    private String cityCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
