package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One payment attempt against a quotation.
 *
 * Many per quotation — a retry after a declined card is a new row, which is
 * what makes this a log rather than a state field. `invoiceId` is unique: it is
 * the reference handed to the payment gateway.
 */
@Entity
@Table(name = "quotation_payment_attempts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationPaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(name = "invoice_id", nullable = false, unique = true, length = 20)
    private String invoiceId;

    @Column(name = "authorization_token", length = 255)
    private String authorizationToken;

    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;
}
