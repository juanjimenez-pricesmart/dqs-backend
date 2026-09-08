package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Delivery details of a quotation — one row per quotation.
 *
 * Mapped from the live `quotation_delivery` schema, not from
 * migration_delivery.sql: that file predates three columns the code has been
 * using all along (`route_name`, `logcargueid`, `pallets`). migration_
 * quotation_delivery_missing_columns.sql closes the gap. Under
 * `ddl-auto=validate` the entity has to match the database exactly or the
 * application does not start, so the column list here came from SHOW COLUMNS.
 *
 * Not to be confused with `quotation_deliveries` (plural), which was declared
 * in migration_quotecenter_schema.sql, never created in any database, and has
 * been removed.
 */
@Entity
@Table(name = "quotation_delivery")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false, unique = true)
    private Quotation quotation;

    @Column(name = "qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal qty;

    @Column(name = "sign_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal signPrice;

    /** Computed on save as qty × signPrice; never sent by the client. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "hour_from", columnDefinition = "TINYINT")
    private Integer hourFrom;

    @Column(name = "hour_to", columnDefinition = "TINYINT")
    private Integer hourTo;

    @Column(name = "ring", length = 50)
    private String ring;

    @Column(name = "box", length = 50)
    private String box;

    /**
     * Route key as a string. The 3NF design turned this into an integer FK
     * against a `routes` table; neither that column nor that table exists, so
     * this stays the legacy `ps_rutas.llave` value it has always held.
     */
    @Column(name = "route_id", length = 20)
    private String routeId;

    /** Snapshot of the route description at the time of quoting. */
    @Column(name = "route_name", length = 200)
    private String routeName;

    @Column(name = "pallets", precision = 10, scale = 2)
    private BigDecimal pallets;

    /**
     * The delivery city, as the catalog identifies it — delivery_cities.id, the
     * legacy ps_delivery_ciudades.idco. A String like routeId beside it, and for
     * the same reason: the panel resolves the selection as a string and the
     * catalog serves it as one, whichever side of the catalog flag it comes from.
     */
    @Column(name = "city_code", length = 20)
    private String cityCode;

    /** Snapshot of the city name at the time of quoting. */
    @Column(name = "city_name", length = 100)
    private String cityName;

    /** Load id assigned when the delivery is dispatched; written elsewhere. */
    @Column(name = "logcargueid")
    private Double logCargueId;

    // The database owns these two: DEFAULT CURRENT_TIMESTAMP and ON UPDATE.
    // Marked non-insertable and non-updatable so Hibernate never overwrites
    // them with nulls.
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
