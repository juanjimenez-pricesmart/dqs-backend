package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One file attached to a quotation.
 *
 * Legacy holds this as two columns on the order — orders.invoice_pic for the
 * file name and orders.documents_folder_url for the folder — which means one
 * attachment per quotation and no record of who added it or when. A row per
 * file gives the payment voucher the several files its input has always
 * accepted (`product_image[]` is a multiple), and leaves room for the invoice
 * and purchase-order types already seeded beside it.
 */
@Entity
@Table(name = "quotation_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_type_id", nullable = false)
    private DocumentType documentType;

    /** A human number — invoice no, PO no. Unused by the voucher. */
    @Column(name = "reference_number", length = 50)
    private String referenceNumber;

    /** What the operator's file was called, for showing it back to them. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "storage_url", nullable = false, length = 500)
    private String storageUrl;

    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
