-- ─────────────────────────────────────────────────────────────────────────────
-- Payment vouchers (B2B-707)
--
-- document_types and quotation_documents are DECLARED in
-- migration_quotecenter_schema.sql but were never created: that file was only
-- partially applied, and `SHOW TABLES` in quotes_dqs_osys_dev finds neither.
-- This creates them, with the same shape the schema file describes, so the
-- entities mapping them pass `spring.jpa.hibernate.ddl-auto=validate`.
--
-- Idempotent on purpose — IF NOT EXISTS on the tables and INSERT IGNORE on the
-- seeds — because the schema file may yet be applied in full somewhere else.
-- If it is, this becomes a no-op rather than a conflict.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS document_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_document_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO document_types (code, name) VALUES
  ('INVOICE',          'Invoice'),
  ('INVOICE_IMAGE',    'Invoice image'),
  ('PURCHASE_ORDER',   'Purchase order (ODC)'),
  ('PROOF_OF_PAYMENT', 'Proof of payment'),
  ('OTHER',            'Other');

-- One row per attached file. Replaces legacy's orders.invoice_pic filename
-- column and its orders.documents_folder_url folder pointer.
CREATE TABLE IF NOT EXISTS quotation_documents (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id        BIGINT        NOT NULL,
    document_type_id    INT           NOT NULL,
    reference_number    VARCHAR(50)   NULL COMMENT 'human number: invoice no, PO no',
    file_name           VARCHAR(255)  NULL,
    storage_url         VARCHAR(500)  NOT NULL COMMENT 'S3 object URL',
    uploaded_by_user_id BIGINT        NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_qdoc_quotation_type (quotation_id, document_type_id),
    CONSTRAINT fk_qdoc_quotation FOREIGN KEY (quotation_id)        REFERENCES quotations (id),
    CONSTRAINT fk_qdoc_type      FOREIGN KEY (document_type_id)    REFERENCES document_types (id),
    CONSTRAINT fk_qdoc_user      FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
