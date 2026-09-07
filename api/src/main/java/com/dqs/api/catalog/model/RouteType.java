package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Route category — Home_Delivery and friends. Was `ps_tipos_ruta`. */
@Entity
@Table(name = "route_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RouteType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
