package com.dqs.api.repository;

import com.dqs.api.model.MemberContactOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Ours (migration_quote_comment_member_overrides.sql), so JPA — see repository/CLAUDE.md. */
public interface MemberContactOverrideRepository extends JpaRepository<MemberContactOverride, Long> {

    Optional<MemberContactOverride> findByMembershipNumber(String membershipNumber);
}
