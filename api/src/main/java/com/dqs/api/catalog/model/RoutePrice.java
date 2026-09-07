package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** One tariff tier of a route. Was a column on `ps_rutas`. */
@Entity
@Table(name = "route_prices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoutePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    /**
     * Stored as its name, matching the CHECK constraint on the column. An
     * ordinal would silently rebind every existing row the moment someone
     * reorders the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20)
    private RouteUnitType unitType;

    @Column(name = "price_local", nullable = false, precision = 15, scale = 4)
    private BigDecimal priceLocal;

    @Column(name = "price_usd", nullable = false, precision = 15, scale = 4)
    private BigDecimal priceUsd;

    /**
     * Smallest billable quantity at this tier, or null when there is none.
     *
     * The legacy `pallet_required` / `halfpallet_required` columns. The 3NF
     * draft had turned them into booleans on the route, which would have thrown
     * the numbers away — they are counts (4 full pallets, 15 half) and the
     * delivery panel prints them. They belong to the tier, which is also why the
     * quarter-pallet tier has none.
     */
    @Column(name = "minimum_quantity")
    private Integer minimumQuantity;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
