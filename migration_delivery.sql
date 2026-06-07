-- ─────────────────────────────────────────────────────────────────────────────
-- Migration: quotation_delivery
-- Stores delivery metadata for quotes that include item 888905
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS quotation_delivery (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quotation_id  BIGINT        NOT NULL UNIQUE,
    qty           DECIMAL(15,4) NOT NULL DEFAULT 1,
    sign_price    DECIMAL(15,4) NOT NULL DEFAULT 0,
    amount        DECIMAL(15,4) NOT NULL DEFAULT 0,   -- computed: qty × sign_price
    address       TEXT          DEFAULT NULL,
    delivery_date DATE          DEFAULT NULL,
    hour_from     TINYINT       DEFAULT NULL,
    hour_to       TINYINT       DEFAULT NULL,
    ring          VARCHAR(50)   DEFAULT NULL,
    box           VARCHAR(50)   DEFAULT NULL,
    route_id      VARCHAR(20)   DEFAULT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_qdel_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Run this if the table was already created without qty/sign_price:
-- ALTER TABLE quotation_delivery
--   ADD COLUMN qty        DECIMAL(15,4) NOT NULL DEFAULT 1    AFTER quotation_id,
--   ADD COLUMN sign_price DECIMAL(15,4) NOT NULL DEFAULT 0    AFTER qty;
