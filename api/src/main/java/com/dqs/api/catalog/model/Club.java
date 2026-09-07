package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A club. Was `ps_tienda`.
 *
 * `clubNumber` is the operational code the rest of the world uses — 6101, 6410
 * — and is deliberately not the primary key: the items API, OMS and the
 * frontend all key off it, so it stays a stable business identifier while `id`
 * stays a surrogate.
 */
@Entity
@Table(name = "clubs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Club {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "club_number", nullable = false)
    private Integer clubNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "address", length = 250)
    private String address;

    @Column(name = "phone", length = 70)
    private String phone;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Printed on quotes. Was ps_tienda.nit. */
    @Column(name = "tax_registration_number", length = 30)
    private String taxRegistrationNumber;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
