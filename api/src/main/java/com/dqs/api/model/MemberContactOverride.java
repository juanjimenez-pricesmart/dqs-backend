package com.dqs.api.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Staff corrections to a member's contact data, overlaid on top of the external
 * Business API when a membership is read.
 *
 * Legacy keeps these on ps_socios: Model_orders::savedatamembership upserts the
 * row and Orders::buscarmembresia then overwrites addressLine1, cellPhone,
 * email and businessName on the API response with whatever that row holds. We
 * keep the behaviour and move the storage — ps_socios belongs to the
 * application we are replacing, and our read path is the API, not that row.
 *
 * One row per membership, so the correction is shared by every quotation for
 * that member, exactly as legacy's single ps_socios row is.
 */
@Entity
@Table(name = "member_contact_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MemberContactOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membership_number", nullable = false, length = 50)
    private String membershipNumber;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "address_line1", length = 250)
    private String addressLine1;

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "created_by_user_id")
    private Integer createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
