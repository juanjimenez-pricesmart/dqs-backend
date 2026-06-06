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

    @CreationTimestamp
    @Column(name = "date_time", updatable = false)
    private LocalDateTime dateTime;

    @Column(name = "dexpired")
    private LocalDate dexpired;

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
