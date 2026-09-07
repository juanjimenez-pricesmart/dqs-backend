package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A country QuoteCenter operates in. Parent of every other catalog.
 *
 * The legacy schema had no country table: these facts were repeated on every
 * ps_tienda row, or did not exist at all.
 */
@Entity
@Table(name = "countries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Country {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "iso2", nullable = false, length = 2)
    private String iso2;

    @Column(name = "iso3", nullable = false, length = 3)
    private String iso3;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "currency_code", nullable = false, length = 3)
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

    /** NIT, RUC, RTN — what the tax id is called here. */
    @Column(name = "tax_id_label", length = 10)
    private String taxIdLabel;

    /**
     * Whether stored line amounts already contain the tax.
     *
     * From the legacy ps_tienda.impuesto_operacion: '+' meant total = subtotal +
     * tax (so amounts exclude it), anything else meant total = subtotal - tax.
     * Read by the OMS payload builder — see azure/README.md, and the open
     * question about the three countries whose legacy value was empty.
     */
    @Column(name = "price_includes_tax", nullable = false)
    private Boolean priceIncludesTax;

    @Column(name = "weight_unit", length = 10)
    private String weightUnit;

    @Column(name = "volume_unit", length = 10)
    private String volumeUnit;

    @Column(name = "min_quote_amount_usd", nullable = false, precision = 15, scale = 4)
    private BigDecimal minQuoteAmountUsd;

    /** Business-supplied authoritative value, not a conversion of the USD one. */
    @Column(name = "min_quote_amount_local", precision = 15, scale = 4)
    private BigDecimal minQuoteAmountLocal;

    @Column(name = "transfer_notice_amount_usd", nullable = false, precision = 15, scale = 4)
    private BigDecimal transferNoticeAmountUsd;

    @Column(name = "transfer_notice_amount_local", precision = 15, scale = 4)
    private BigDecimal transferNoticeAmountLocal;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by a database trigger; see azure/01_schema.sql. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
