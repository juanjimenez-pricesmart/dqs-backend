-- ─────────────────────────────────────────────────────────────────────────────
-- quotations.comments + member_contact_overrides
--
-- Two pieces of data the edit screen has always captured and never stored.
-- Both were living in the browser's localStorage as a stopgap, which means they
-- were invisible to the PDF, to the next user, and to the same user on another
-- machine.
--
-- 1. quotations.comments — the header note the operator types under the item
--    table. Legacy keeps it on orders.comments (Orders::savecomment with
--    item == 0) and prints it on the quotation PDF. The column is already
--    specified in migration_quotecenter_schema.sql:634; it never reached the
--    narrowed table that migration_normalize_3nf.sql left behind, so it is
--    added here with the same type and comment.
--
-- 2. member_contact_overrides — staff corrections to member contact data.
--    Legacy upserts ps_socios (Model_orders::savedatamembership) and then
--    overlays those same four fields on top of the Business API response when
--    reading a membership (Orders::buscarmembresia). We keep the behaviour and
--    change the table: ps_socios belongs to the application we are replacing,
--    and writing to it would also be invisible on our side, because our read
--    path is the Business API and not that row.
--
--    The DDL is the one already designed in migration_quotecenter_schema.sql
--    :471, with one correction: created_by_user_id is INT, not BIGINT. The
--    designed schema assumed a BIGINT users.id; the live users.id is INT, and
--    MySQL rejects the foreign key on the mismatch (error 3780).
--
-- IMPORTANT — apply this before deploying the code that goes with it.
-- spring.jpa.hibernate.ddl-auto is `validate`: Quotation.comments and the
-- MemberContactOverride entity are checked against the live schema at startup,
-- and an environment without these will not start.
--
-- Touches only QuoteCenter tables. No legacy table is created, altered or read
-- by this migration.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE quotations
    ADD COLUMN comments TEXT NULL COMMENT 'header note printed on PDF' AFTER status_id;

CREATE TABLE IF NOT EXISTS member_contact_overrides (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    membership_number   VARCHAR(50)   NOT NULL,
    email               VARCHAR(150)  NULL,
    phone               VARCHAR(30)   NULL,
    address_line1       VARCHAR(250)  NULL,
    business_name       VARCHAR(255)  NULL,
    created_by_user_id  INT           NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_mco_membership (membership_number),
    CONSTRAINT fk_mco_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Staff-corrected member contact data; overlays the external Business API';
