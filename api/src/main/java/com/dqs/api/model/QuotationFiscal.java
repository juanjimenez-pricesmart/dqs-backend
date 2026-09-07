package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Fiscal / electronic-invoicing details of a quotation — one row per quotation.
 *
 * The `*_code` fields are deliberately plain strings rather than associations
 * to City, Zone, Neighborhood and EconomicActivity. Those four are catalogs fed
 * by whatever GoSocket returns: FiscalService upserts a row on save if the code
 * is new. Modelling them as FKs would make saving a quotation fail whenever the
 * provider sends a code we have not seen yet, which is precisely the case the
 * upsert exists to handle.
 */
@Entity
@Table(name = "quotation_fiscal")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationFiscal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false, unique = true)
    private Quotation quotation;

    @Column(name = "membership", length = 50)
    private String membership;

    @Column(name = "country", nullable = false, length = 10)
    private String country;

    @Column(name = "document_type", length = 10)
    private String documentType;

    @Column(name = "document_number", length = 50)
    private String documentNumber;

    /**
     * The column is TINYINT(1), which the MySQL driver reports as BIT, so this
     * has to be a Boolean or schema validation fails at startup. The API still
     * carries it as 0/1 — FiscalService converts at both edges.
     */
    @Column(name = "document_validated", nullable = false)
    private Boolean documentValidated;

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "nrc", length = 30)
    private String nrc;

    @Column(name = "economic_activity_code", length = 20)
    private String economicActivityCode;

    @Column(name = "city_code", length = 20)
    private String cityCode;

    @Column(name = "zone_code", length = 20)
    private String zoneCode;

    @Column(name = "neighborhood_code", length = 20)
    private String neighborhoodCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private java.time.Instant updatedAt;
}
