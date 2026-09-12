-- ─────────────────────────────────────────────────────────────────────────────
-- A voucher can exist without its bytes (B2B-707)
--
-- quotation_documents.storage_url was NOT NULL, which assumed every attachment
-- reaches S3. It does not: legacy's AWSService returns `aws_disabled` when
-- ENABLE_AWS_S3 is off and its controller reports that to the browser as a
-- success, so the file is silently kept nowhere. S3 is not enabled in the
-- legacy production environment today and there is no date for it.
--
-- Our upload refused outright in that case, which was worse than legacy rather
-- than better: the close gate counts voucher rows, so with storage off no row
-- could ever be written and no sale could ever be closed.
--
-- NULL now means "a voucher was provided and validated, and we do not have the
-- file". That is what legacy's flow actually produces, minus its amnesia — it
-- records nothing at all, so nobody can tell afterwards which quotations were
-- closed against a voucher that went nowhere. These rows can.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE quotation_documents
  MODIFY COLUMN storage_url VARCHAR(500) NULL
  COMMENT 'S3 object URL; NULL = accepted but not stored (storage disabled or unreachable)';
