-- ─────────────────────────────────────────────────────────────────────────────
-- Migration: FEL / Electronic Invoice tables
-- Covers: fiscal data per quotation + geographic/economic catalog tables
-- ─────────────────────────────────────────────────────────────────────────────

-- ─── 1. Catalog: economic activities ─────────────────────────────────────────
-- One row per (code, country). Populated on first use via upsert.

CREATE TABLE IF NOT EXISTS economic_activities (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL,
    value      VARCHAR(255) NOT NULL,
    country    VARCHAR(10)  NOT NULL,
    CONSTRAINT uq_ea_code_country UNIQUE (code, country)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ─── 2. Catalog: cities ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS cities (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    country    VARCHAR(10)  NOT NULL,
    CONSTRAINT uq_city_code_country UNIQUE (code, country)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ─── 3. Catalog: zones ────────────────────────────────────────────────────────
-- A zone belongs to a city.

CREATE TABLE IF NOT EXISTS zones (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    city_code   VARCHAR(20)  NOT NULL,
    CONSTRAINT uq_zone_code_city UNIQUE (code, city_code)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ─── 4. Catalog: neighborhoods ───────────────────────────────────────────────
-- A neighborhood belongs to a zone + city.

CREATE TABLE IF NOT EXISTS neighborhoods (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(20)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    zone_code         VARCHAR(20)  NOT NULL,
    city_code         VARCHAR(20)  NOT NULL,
    CONSTRAINT uq_neighborhood_code_zone_city UNIQUE (code, zone_code, city_code)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ─── 5. Main: quotation_fiscal ───────────────────────────────────────────────
-- One row per quotation. Stores the fiscal/FEL data submitted at quote time.

CREATE TABLE IF NOT EXISTS quotation_fiscal (
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quotation_id            BIGINT       NOT NULL UNIQUE,
    membership              VARCHAR(50)  DEFAULT NULL,
    country                 VARCHAR(10)  NOT NULL,

    -- Document identification
    document_type           VARCHAR(10)  DEFAULT NULL,   -- NIT | CUI | NRC
    document_number         VARCHAR(50)  DEFAULT NULL,
    document_validated      TINYINT(1)   NOT NULL DEFAULT 0,

    -- Taxpayer info (GT / general)
    business_name           VARCHAR(255) DEFAULT NULL,
    address                 TEXT         DEFAULT NULL,
    phone                   VARCHAR(30)  DEFAULT NULL,
    email                   VARCHAR(150) DEFAULT NULL,

    -- El Salvador
    nrc                     VARCHAR(30)  DEFAULT NULL,

    -- Catalog references (nullable — not all countries require all fields)
    economic_activity_code  VARCHAR(20)  DEFAULT NULL,
    city_code               VARCHAR(20)  DEFAULT NULL,
    zone_code               VARCHAR(20)  DEFAULT NULL,
    neighborhood_code       VARCHAR(20)  DEFAULT NULL,

    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_qf_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
