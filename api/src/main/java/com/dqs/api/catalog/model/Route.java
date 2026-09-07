package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A delivery route for a club. Was `ps_rutas`.
 *
 * `code` is the legacy `llave`, unique only within a club — hence the composite
 * unique key on (club, code) rather than a global one.
 */
@Entity
@Table(name = "routes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id", nullable = false)
    private Club club;

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_type_id")
    private RouteType routeType;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "truck_size", nullable = false, precision = 10, scale = 2)
    private BigDecimal truckSize;

    @Column(name = "requires_full_pallet", nullable = false)
    private Boolean requiresFullPallet;

    @Column(name = "requires_half_pallet", nullable = false)
    private Boolean requiresHalfPallet;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @OneToMany(mappedBy = "route", fetch = FetchType.LAZY)
    @Builder.Default
    private List<RoutePrice> prices = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
