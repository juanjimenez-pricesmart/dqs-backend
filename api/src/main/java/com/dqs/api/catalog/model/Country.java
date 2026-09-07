package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A country QuoteCenter operates in. Parent of every other catalog.
 *
 * The table predates this work — migration_quotecenter_schema.sql created it
 * and seeded the thirteen countries — so the mapping follows what is there
 * rather than what that file's fuller draft described. `code` already holds the
 * ISO2 (CR, CO, GT…), which is why there is no separate iso2 column.
 */
@Entity
@Table(name = "countries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** ISO 3166-1 alpha-2. What every other catalog joins on. */
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "country_name_full", length = 100)
    private String fullName;

    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    @Column(name = "currency_symbol", length = 10)
    private String currencySymbol;

    @Column(name = "currency_name", length = 50)
    private String currencyName;

    @Column(name = "default_language", nullable = false, length = 2)
    private String defaultLanguage;

    /** IVA, VAT, ITBMS… Its own column, unlike in the legacy schema. */
    @Column(name = "tax_name", length = 20)
    private String taxName;

    /**
     * Whether stored line amounts already contain the tax.
     *
     * From the legacy ps_tienda.impuesto_operacion: '+' meant total = subtotal +
     * tax, so amounts exclude it; anything else meant total = subtotal - tax.
     * Read by the OMS payload builder. The three countries whose legacy value
     * was empty are an open question — see the parity document.
     */
    @Column(name = "price_includes_tax", nullable = false)
    private Boolean priceIncludesTax;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
