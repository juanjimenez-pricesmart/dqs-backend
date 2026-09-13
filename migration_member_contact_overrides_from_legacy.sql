-- ---------------------------------------------------------------------------
-- !! DO NOT RUN WITHOUT REVIEWING THE WARNING BELOW !!
--
-- Migration: import staff-edited member contact overrides from legacy ps_socios
--
-- WARNING
-- -------
-- ps_socios stores TWO kinds of data that cannot be distinguished by the WHERE
-- clause below:
--   1. Staff corrections — explicit edits via the member-contact modal
--   2. Business API snapshots — cached at quote-creation time from the API
--
-- Running this script copies both kinds. Snapshots from 2024-2026 would become
-- permanent overrides and freeze stale addresses/emails in DQS, hiding any
-- subsequent changes made in the Business API.
--
-- PREFERRED APPROACH (already implemented in MemberService.applyLegacyFallback):
-- DQS reads ps_socios as a live fallback at request time instead of migrating.
-- This shows the same data as legacy without freezing anything. Only run this
-- script if you have a reliable way to isolate genuine staff corrections.
--
-- Context
-- -------
-- The legacy app (QuoteCenter) stores two kinds of data in ps_socios:
--   1. A snapshot of the Business API response (firstName, lastName, etc.)
--   2. Staff corrections to four editable fields — addressLine1, cellPhone,
--      email, businessName — written by Orders::savedatamembership / the
--      member-contact modal.
--
-- DQS reads member data directly from the Business API and overlays the four
-- correctable fields from member_contact_overrides (MemberService.java).
-- Without this migration, every staff correction made in the legacy app is
-- invisible in DQS, so members show their raw Business API data instead of
-- the operator-corrected values.
--
-- What this script does
-- ---------------------
-- Copies every ps_socios row that has at least one non-empty correctable
-- field into member_contact_overrides. Rows already present in
-- member_contact_overrides are left unchanged (ON DUPLICATE KEY does nothing),
-- so the script is safe to re-run.
--
-- Column mapping
-- --------------
-- ps_socios.membership    → member_contact_overrides.membership_number
-- ps_socios.addressLine1  → member_contact_overrides.address_line1
-- ps_socios.cellPhone     → member_contact_overrides.phone
-- ps_socios.email         → member_contact_overrides.email
-- ps_socios.businessName  → member_contact_overrides.business_name
-- created_by_user_id      → 0  (legacy has no user tracking on this table)
--
-- Prerequisites
-- -------------
-- Run against the DQS database. ps_socios must be accessible from the same
-- connection (same database or a reachable schema — adjust the schema prefix
-- below if needed).
-- ---------------------------------------------------------------------------

INSERT INTO member_contact_overrides (
    membership_number,
    address_line1,
    phone,
    email,
    business_name,
    created_by_user_id,
    created_at,
    updated_at
)
SELECT
    s.membership                                AS membership_number,
    NULLIF(TRIM(s.addressLine1), '')            AS address_line1,
    NULLIF(TRIM(s.cellPhone),    '')            AS phone,
    NULLIF(TRIM(s.email),        '')            AS email,
    NULLIF(TRIM(s.businessName), '')            AS business_name,
    0                                           AS created_by_user_id,
    NOW()                                       AS created_at,
    NOW()                                       AS updated_at
FROM ps_socios s
WHERE
    -- Only migrate rows where staff actually entered at least one override.
    -- Rows with all four fields empty or null are Business API snapshots
    -- with no staff correction and produce no visible difference in DQS.
    (
        NULLIF(TRIM(s.addressLine1), '') IS NOT NULL OR
        NULLIF(TRIM(s.cellPhone),    '') IS NOT NULL OR
        NULLIF(TRIM(s.email),        '') IS NOT NULL OR
        NULLIF(TRIM(s.businessName), '') IS NOT NULL
    )
ON DUPLICATE KEY UPDATE
    -- A row already present in member_contact_overrides was saved through
    -- DQS and is more recent than the legacy snapshot — leave it untouched.
    membership_number = member_contact_overrides.membership_number;
