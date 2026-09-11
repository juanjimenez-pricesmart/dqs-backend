package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * What a {@link QuotationDocument} is: an invoice, a purchase order, a proof of
 * payment. Seeded by migration_quotation_documents.sql; the codes are stable
 * and are what code looks the rows up by.
 */
@Entity
@Table(name = "document_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentType {

    /** The payment voucher. Legacy's single orders.invoice_pic column. */
    public static final String PROOF_OF_PAYMENT = "PROOF_OF_PAYMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;
}
