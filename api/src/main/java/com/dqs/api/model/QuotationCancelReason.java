package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Reasons a quotation can be closed without a sale.
 *
 * A fixed, seeded catalog: the id is assigned by whoever seeds the rows, not
 * auto-generated, so there is no @GeneratedValue here.
 */
@Entity
@Table(name = "quotation_cancel_reasons")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationCancelReason {

    @Id
    private Integer id;

    @Column(name = "description", nullable = false, length = 200)
    private String description;
}
