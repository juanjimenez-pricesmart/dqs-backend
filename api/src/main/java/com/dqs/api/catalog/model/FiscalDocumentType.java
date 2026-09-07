package com.dqs.api.catalog.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Fiscal document type for a country. Was `ps_fel`.
 *
 * `code` is the stable identity the per-type number validation keys off — the
 * old `nombre_en`, carrying NIT, CUI, PHYSICAL, LEGAL, DIMEX, NITE. `nameEs` is
 * a label shown to the operator and free to change; nothing should match on it.
 */
@Entity
@Table(name = "fiscal_document_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FiscalDocumentType {

    /**
     * The legacy felid, assigned by the import rather than generated:
     * quotation_fiscal.document_type stores this value, so it has to survive.
     */
    @Id
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name_en", nullable = false, length = 80)
    private String nameEn;

    @Column(name = "name_es", nullable = false, length = 80)
    private String nameEs;

    /** Was ps_fel.formato. */
    @Column(name = "input_mask", length = 150)
    private String inputMask;

    @Column(name = "validation_regex", length = 150)
    private String validationRegex;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
