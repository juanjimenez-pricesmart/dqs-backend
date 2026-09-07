package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Local units per 1 USD, for a country on a date. Was `ps_tasa_cambio`.
 *
 * Append-only, one row per country per day. The legacy table also scoped rates
 * per store and every caller ignored that, so the dimension is gone.
 *
 * A quote snapshots the rate it used, so this is never consulted to reprice
 * something after the fact — only to find today's rate.
 */
@Entity
@Table(name = "exchange_rates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "rate", nullable = false, precision = 15, scale = 6)
    private BigDecimal rate;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "source", length = 100)
    private String source;

    /** A `users.id`, and a real foreign key — same database. */
    @Column(name = "created_by_user_id")
    private Integer createdByUserId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
