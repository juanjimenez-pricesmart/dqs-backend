package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "quotations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Integer storeId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "status_id")
    private Integer statusId;

    /**
     * The header note the operator types under the item table, printed on the
     * quotation PDF. Legacy's orders.comments, written by Orders::savecomment
     * when item == 0.
     */
    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @CreationTimestamp
    @Column(name = "date_time", updatable = false)
    private LocalDateTime dateTime;

    @Column(name = "dexpired")
    private LocalDate expiryDate;

    /**
     * Why the quotation was closed without a sale — a QuotationCancelReason id.
     *
     * Held as a plain id rather than an association: it is set once, at close
     * time, and every read of it goes through the reasons catalog anyway.
     */
    @Column(name = "cancel_reason_id")
    private Integer cancelReasonId;

    /**
     * The campaign this quotation belongs to — legacy's orders.temporada_id, a
     * ps_temporada.tid. Held as a plain id rather than an association: that
     * catalog belongs to the application we are replacing and is read through
     * NativeQueries, so there is no entity to point at.
     *
     * NULL means no season. Legacy spells the same thing both 0 and NULL.
     */
    @Column(name = "season_id")
    private Integer seasonId;

    /**
     * The Callejas purchase order this quotation was imported from — legacy's
     * orders.odc. NULL for every quotation this application creates today; the
     * importer that would write it is not built yet.
     *
     * It is not just provenance: a quotation carrying one prints its lines
     * grouped by product-code prefix regardless of the sort radio. See
     * {@link com.dqs.api.util.QuoteItemSort#order}.
     */
    @Column(name = "odc")
    private Long odc;

    @OneToOne(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    private QuotationCustomer customer;

    @OneToOne(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    private QuotationTotals totals;

    @OneToOne(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    private QuotationPayment payment;

    @Builder.Default
    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuotationItem> items = new ArrayList<>();
}
