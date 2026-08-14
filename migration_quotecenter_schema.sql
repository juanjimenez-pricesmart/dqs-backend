-- ============================================================================
-- QuoteCenter — initial schema (fresh MySQL 8 database, Azure)
-- B2B-707: Diseño estructura BD nuevo cotizador (QuoteCenter)
--
-- Designed from a full analysis of the legacy DQS database (88 tables) and the
-- current Java backend. 3NF with explicit, documented snapshot tables
-- (quote-time facts are temporal data — justified duplication, not a
-- normalization violation).
--
-- Conventions:
--   * InnoDB, utf8mb4 / utf8mb4_0900_ai_ci everywhere.
--   * English snake_case names only.
--   * Money: DECIMAL(15,4). Rates/percents: DECIMAL(10,4). FX: DECIMAL(15,6).
--     Quantities: DECIMAL(12,4). Never double/float for money.
--   * Every table has a surrogate id PK; every relationship has a real FK.
--   * Lookup rows are resolved BY CODE in the application — no magic ids
--     hardcoded in DDL defaults or services.
--   * Quotations are never hard-deleted (they cancel/expire): all
--     quotation-child FKs are RESTRICT. CASCADE only where a parent row is
--     legitimately deleted in-life (quote line editing, route re-pricing,
--     junction cleanup).
--
-- Historical data policy: the legacy DB (quotes_dqs_osys_dev) stays behind as
-- a read-only archive. Only catalog VALUE SETS are seeded here.
-- ============================================================================

SET NAMES utf8mb4;

-- ────────────────────────────────────────────────────────────────────────────
-- 1. Country master
--    Merges legacy: countries + dqs_country_information + company +
--    ps_monto_minimo_cotizacion + country-level columns misplaced on ps_tienda
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE countries (
    id                              INT           NOT NULL AUTO_INCREMENT,
    iso2                            CHAR(2)       NOT NULL,
    iso3                            CHAR(3)       NOT NULL,
    name                            VARCHAR(100)  NOT NULL,
    currency_code                   CHAR(3)       NOT NULL COMMENT 'ISO 4217 local currency; USD implicit system-wide',
    currency_symbol                 VARCHAR(10)   NULL,
    currency_name                   VARCHAR(50)   NULL,
    default_language                CHAR(2)       NOT NULL DEFAULT 'es',
    tax_name                        VARCHAR(20)   NULL COMMENT 'IVA, VAT, ITBMS…',
    tax_id_label                    VARCHAR(10)   NULL COMMENT 'NIT, RUC, RTN (ex ps_tienda.tributo_siglas)',
    price_includes_tax              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'ex impuesto_operacion; read by OMS payload builder',
    weight_unit                     VARCHAR(10)   NULL,
    volume_unit                     VARCHAR(10)   NULL,
    min_quote_amount_usd            DECIMAL(15,4) NOT NULL DEFAULT 50.0000,
    min_quote_amount_local          DECIMAL(15,4) NULL COMMENT 'business-supplied authoritative value, not a conversion',
    transfer_notice_amount_usd      DECIMAL(15,4) NOT NULL DEFAULT 250.0000,
    transfer_notice_amount_local    DECIMAL(15,4) NULL,
    is_active                       TINYINT(1)    NOT NULL DEFAULT 1,
    created_at                      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_countries_iso2 (iso2),
    UNIQUE KEY uq_countries_iso3 (iso3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 2. Users (IDP mirror — auth lives in the IDP; no password/username here)
--    idp_subject is nullable: populated JIT at first login.
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    idp_subject     CHAR(36)      NULL COMMENT 'IDP sub claim; populated at first login',
    email           VARCHAR(255)  NOT NULL,
    first_name      VARCHAR(100)  NOT NULL,
    last_name       VARCHAR(100)  NOT NULL,
    phone           VARCHAR(30)   NULL,
    is_active       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'local kill-switch',
    last_login_at   DATETIME      NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_idp_subject (idp_subject),
    UNIQUE KEY uq_users_email (email),
    KEY idx_users_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 3. System configuration (key/value; kept from legacy, single natural key)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE system_configurations (
    id            INT           NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(191)  NOT NULL,
    config_value  TEXT          NULL,
    data_type     ENUM('STRING','BOOLEAN','INTEGER','DECIMAL','JSON') NOT NULL DEFAULT 'STRING',
    category      VARCHAR(50)   NOT NULL DEFAULT 'GENERAL',
    description   VARCHAR(255)  NULL,
    is_active     TINYINT(1)    NOT NULL DEFAULT 1,
    created_by    VARCHAR(100)  NULL,
    updated_by    VARCHAR(100)  NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_system_configurations_key (config_key),
    KEY idx_system_configurations_category (category, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 4. Catalogs (lookup tables — rows resolved by code, never by magic id)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE quotation_statuses (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name_en     VARCHAR(100)  NOT NULL,
    name_es     VARCHAR(100)  NOT NULL,
    flag_color  VARCHAR(15)   NULL COMMENT 'UI badge color (ex orders_status.flagcolor)',
    sort_order  INT           NOT NULL DEFAULT 0,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotation_statuses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Origin channel of a quotation: club staff vs member self-service (tablet/
-- phone/PC — device is irrelevant, the channel is the same). New channels
-- (e.g. ECOMMERCE, PROSPECTING) are rows, never DDL.
CREATE TABLE quotation_channels (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name_en     VARCHAR(100)  NOT NULL,
    name_es     VARCHAR(100)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotation_channels_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE quote_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(50)   NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quote_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE quotation_cancel_reasons (
    id          INT           NOT NULL AUTO_INCREMENT,
    description VARCHAR(200)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    sort_order  INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tax_types (
    id            INT           NOT NULL AUTO_INCREMENT,
    code          VARCHAR(20)   NOT NULL COMMENT 'IVA, VAT, ICO, EXEMPTION, WITHHOLDING',
    name_en       VARCHAR(100)  NOT NULL,
    name_es       VARCHAR(100)  NOT NULL,
    reduces_total TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 for EXEMPTION and WITHHOLDING',
    is_active     TINYINT(1)    NOT NULL DEFAULT 1,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tax_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_statuses (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name_en     VARCHAR(100)  NOT NULL,
    name_es     VARCHAR(100)  NOT NULL,
    is_terminal TINYINT(1)    NOT NULL DEFAULT 0,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_statuses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_gateways (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_gateways_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_attempt_statuses (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)   NOT NULL,
    name_en     VARCHAR(100)  NOT NULL,
    name_es     VARCHAR(100)  NOT NULL,
    is_terminal TINYINT(1)    NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_attempt_statuses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE oms_statuses (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(10)   NOT NULL,
    name_en     VARCHAR(100)  NOT NULL,
    name_es     VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_statuses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Display translations for external OMS status codes; nothing FKs to it';

CREATE TABLE document_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_document_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE route_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(200)  NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_route_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(255)  NOT NULL DEFAULT '',
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_roles_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(100)  NOT NULL COMMENT 'resource.action, e.g. quotation.create',
    description VARCHAR(255)  NOT NULL DEFAULT '',
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_permissions_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 5. Clubs (ex ps_tienda, decomposed; legacy `stores` table dropped)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE clubs (
    id                      INT           NOT NULL AUTO_INCREMENT,
    club_number             INT           NOT NULL COMMENT 'operational code (ex ps_tienda_id, e.g. 6410); key used by OMS and items API',
    country_id              INT           NOT NULL,
    name                    VARCHAR(100)  NOT NULL,
    address                 VARCHAR(250)  NULL,
    phone                   VARCHAR(70)   NULL COMMENT 'may hold several numbers/extensions (legacy max 60 chars)',
    latitude                DECIMAL(9,6)  NULL,
    longitude               DECIMAL(9,6)  NULL,
    tax_registration_number VARCHAR(30)   NULL COMMENT 'printed on quotes (ex ps_tienda.nit)',
    timezone                VARCHAR(64)   NOT NULL COMMENT 'IANA name (ex zonahoraria)',
    is_active               TINYINT(1)    NOT NULL DEFAULT 1,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_clubs_club_number (club_number),
    KEY idx_clubs_country_active (country_id, is_active),
    CONSTRAINT fk_clubs_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 6. Geo catalogs (FEL): countries → cities → zones → neighborhoods
--    Integer FK chain; the extra UNIQUE(id, parent) keys exist so deeper
--    consumers can declare composite FKs that make cross-level inconsistency
--    impossible (3NF review fix).
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE cities (
    id          INT           NOT NULL AUTO_INCREMENT,
    country_id  INT           NOT NULL,
    code        VARCHAR(20)   NOT NULL COMMENT 'official FEL catalog code',
    name        VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cities_country_code (country_id, code),
    KEY idx_cities_name (name),
    CONSTRAINT fk_cities_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE zones (
    id          INT           NOT NULL AUTO_INCREMENT,
    city_id     INT           NOT NULL,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_zones_city_code (city_id, code),
    UNIQUE KEY uq_zones_id_city (id, city_id),
    KEY idx_zones_name (name),
    CONSTRAINT fk_zones_city FOREIGN KEY (city_id) REFERENCES cities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE neighborhoods (
    id          INT           NOT NULL AUTO_INCREMENT,
    zone_id     INT           NOT NULL,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_neighborhoods_zone_code (zone_id, code),
    UNIQUE KEY uq_neighborhoods_id_zone (id, zone_id),
    KEY idx_neighborhoods_name (name),
    CONSTRAINT fk_neighborhoods_zone FOREIGN KEY (zone_id) REFERENCES zones (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE economic_activities (
    id          INT           NOT NULL AUTO_INCREMENT,
    country_id  INT           NOT NULL,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_economic_activities_country_code (country_id, code),
    CONSTRAINT fk_economic_activities_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Fiscal document types (ex ps_fel). Stable code is the identity (3NF review
-- fix); name_en/name_es are display-only. Preserves B2B-646 CR type set.
CREATE TABLE fiscal_document_types (
    id               INT               NOT NULL AUTO_INCREMENT,
    country_id       INT               NOT NULL,
    code             VARCHAR(30)       NOT NULL COMMENT 'stable identity: NIT, DUI, CEDULA_FISICA, CEDULA_JURIDICA, DIMEX…',
    name_en          VARCHAR(80)       NOT NULL COMMENT 'display + OMS EI-IdType value',
    name_es          VARCHAR(80)       NOT NULL,
    min_length       SMALLINT UNSIGNED NULL,
    max_length       SMALLINT UNSIGNED NULL,
    input_mask       VARCHAR(150)      NULL COMMENT 'ex ps_fel.formato',
    validation_regex VARCHAR(150)      NULL COMMENT 'ex ps_fel.formato2',
    is_active        TINYINT(1)        NOT NULL DEFAULT 1,
    created_at       TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_fdt_country_code (country_id, code),
    KEY idx_fdt_country_active (country_id, is_active),
    CONSTRAINT fk_fdt_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Payment method concept catalog (ex orders_pago names, defined ONCE).
-- The per-country row below carries only what genuinely varies by country.
CREATE TABLE payment_method_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)   NOT NULL COMMENT 'VISA, EFECTIVO, PAYMENT_LINK…',
    name        VARCHAR(100)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_method_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Per-country availability + tender mapping (ex orders_pago rows).
-- tender_key feeds the OMS payload and DOES vary by country for some methods
-- (e.g. cash has 5 different tender keys across countries).
CREATE TABLE country_payment_methods (
    id              INT           NOT NULL AUTO_INCREMENT,
    country_id      INT           NOT NULL,
    method_type_id  INT           NOT NULL,
    tender_key      INT           NOT NULL COMMENT 'POS/OMS tender key',
    is_active       TINYINT(1)    NOT NULL DEFAULT 1,
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpm_country_method (country_id, method_type_id),
    KEY idx_cpm_country_active (country_id, is_active),
    CONSTRAINT fk_cpm_country     FOREIGN KEY (country_id)     REFERENCES countries (id),
    CONSTRAINT fk_cpm_method_type FOREIGN KEY (method_type_id) REFERENCES payment_method_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only daily FX (ex ps_tasa_cambio; per-store scoping dropped — dead
-- weight, services query by country). Quotes SNAPSHOT the rate they used.
CREATE TABLE exchange_rates (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    country_id          INT           NOT NULL,
    rate                DECIMAL(15,6) NOT NULL COMMENT 'local units per 1 USD',
    effective_date      DATE          NOT NULL,
    source              VARCHAR(100)  NULL,
    created_by_user_id  BIGINT        NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_exchange_rates_country_date (country_id, effective_date),
    CONSTRAINT fk_exchange_rates_country FOREIGN KEY (country_id) REFERENCES countries (id),
    CONSTRAINT fk_exchange_rates_user FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 7. RBAC (replaces groups1/group_permissions/applications/group_applications/
--    group_application_permissions/user_group/user_store and 2 ps_tienda_personal tables)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE role_permissions (
    id            BIGINT     NOT NULL AUTO_INCREMENT,
    role_id       INT        NOT NULL,
    permission_id INT        NOT NULL,
    created_at    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_role_permissions (role_id, permission_id),
    KEY idx_role_permissions_permission (permission_id),
    CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles (id)       ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_clubs (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    club_id     INT         NOT NULL,
    is_default  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT 'club preselected in UI',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_clubs (user_id, club_id),
    KEY idx_user_clubs_club (club_id),
    CONSTRAINT fk_user_clubs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_clubs_club FOREIGN KEY (club_id) REFERENCES clubs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    user_id             BIGINT      NOT NULL,
    role_id             INT         NOT NULL,
    club_id             INT         NULL COMMENT 'NULL = global scope',
    club_scope_key      INT         AS (IFNULL(club_id, 0)) STORED NOT NULL,
    granted_by_user_id  BIGINT      NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_roles (user_id, role_id, club_scope_key),
    KEY idx_user_roles_role (role_id),
    KEY idx_user_roles_club (club_id),
    CONSTRAINT fk_ur_user       FOREIGN KEY (user_id)            REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role       FOREIGN KEY (role_id)            REFERENCES roles (id),
    CONSTRAINT fk_ur_club       FOREIGN KEY (club_id)            REFERENCES clubs (id),
    CONSTRAINT fk_ur_granted_by FOREIGN KEY (granted_by_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 8. Members (master data lives in the external Business API — locally only
--    staff-corrected overrides and reusable fiscal profiles)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE member_contact_overrides (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    membership_number   VARCHAR(50)   NOT NULL,
    email               VARCHAR(150)  NULL,
    phone               VARCHAR(30)   NULL,
    address_line1       VARCHAR(250)  NULL,
    business_name       VARCHAR(255)  NULL,
    created_by_user_id  BIGINT        NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_mco_membership (membership_number),
    CONSTRAINT fk_mco_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Staff-corrected member contact data; overlays the external Business API';

-- Reusable fiscal-invoice profiles (ex ps_socios_fel profile role + ps_socios_docs).
-- Composite FKs on the geo chain (zone must belong to city, neighborhood to
-- zone) make stored ancestors consistent by construction (3NF review fix).
CREATE TABLE member_fiscal_profiles (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    membership_number       VARCHAR(50)   NOT NULL,
    country_id              INT           NOT NULL,
    document_type_id        INT           NULL,
    document_number         VARCHAR(50)   NOT NULL COMMENT 'NIT/DUI/cédula (ex nit)',
    document_validated      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'validated vs GoSocket',
    business_name           VARCHAR(255)  NOT NULL,
    address                 VARCHAR(500)  NULL,
    phone                   VARCHAR(30)   NULL,
    email                   VARCHAR(150)  NULL,
    nrc                     VARCHAR(30)   NULL COMMENT 'SV Registro de Contribuyente',
    economic_activity_id    INT           NULL,
    city_id                 INT           NULL,
    zone_id                 INT           NULL,
    neighborhood_id         INT           NULL,
    wants_tax_invoice       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'default preference',
    wants_electronic_ticket TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'CR tiquete preference',
    is_active               TINYINT(1)    NOT NULL DEFAULT 1,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_mfp_member_doc (membership_number, country_id, document_number),
    CONSTRAINT fk_mfp_country           FOREIGN KEY (country_id)           REFERENCES countries (id),
    CONSTRAINT fk_mfp_doc_type          FOREIGN KEY (document_type_id)     REFERENCES fiscal_document_types (id),
    CONSTRAINT fk_mfp_economic_activity FOREIGN KEY (economic_activity_id) REFERENCES economic_activities (id),
    CONSTRAINT fk_mfp_city              FOREIGN KEY (city_id)              REFERENCES cities (id),
    CONSTRAINT fk_mfp_zone              FOREIGN KEY (zone_id, city_id)     REFERENCES zones (id, city_id),
    CONSTRAINT fk_mfp_neighborhood      FOREIGN KEY (neighborhood_id, zone_id) REFERENCES neighborhoods (id, zone_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 9. Club configuration satellites (ex ps_tienda column clusters)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE club_texts (
    id          INT         NOT NULL AUTO_INCREMENT,
    club_id     INT         NOT NULL,
    text_type   VARCHAR(40) NOT NULL COMMENT 'QUOTE_TITLE|QUOTE_DISCLAIMER|QUOTE_DISCLAIMER_2|DELIVERY_TITLE|DELIVERY_DISCLAIMER|DDC_DELIVERY_TITLE|DDC_DELIVERY_DISCLAIMER|HOME_DELIVERY_CONTRACT',
    content     MEDIUMTEXT  NOT NULL,
    is_active   TINYINT(1)  NOT NULL DEFAULT 1,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_club_texts (club_id, text_type),
    CONSTRAINT fk_club_texts_club FOREIGN KEY (club_id) REFERENCES clubs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE club_delivery_settings (
    id                              INT         NOT NULL AUTO_INCREMENT,
    club_id                         INT         NOT NULL,
    home_delivery_enabled           TINYINT(1)  NOT NULL DEFAULT 0,
    ddc_delivery_enabled            TINYINT(1)  NOT NULL DEFAULT 0,
    ddc_inventory_enabled           TINYINT(1)  NOT NULL DEFAULT 0,
    home_delivery_contract_required TINYINT(1)  NOT NULL DEFAULT 0,
    show_zones                      TINYINT(1)  NOT NULL DEFAULT 0,
    show_option_descriptions        TINYINT(1)  NOT NULL DEFAULT 0,
    created_at                      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_club_delivery_settings_club (club_id),
    CONSTRAINT fk_cds_club FOREIGN KEY (club_id) REFERENCES clubs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- NOTE: legacy ps_tienda.delivery_op1..op4 positional flags were NOT carried
-- over: no consumer exists in the new backend/frontend (delivery is gated by
-- the quotecenter.delivery.enabled flag + per-club settings above), and their
-- business meaning is undocumented. If delivery options return, add a
-- club_delivery_options table with named codes.

CREATE TABLE club_notification_recipients (
    id          INT           NOT NULL AUTO_INCREMENT,
    club_id     INT           NOT NULL,
    email       VARCHAR(255)  NOT NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_club_notification_recipients (club_id, email),
    CONSTRAINT fk_cnr_club FOREIGN KEY (club_id) REFERENCES clubs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- NOTE: legacy GlobalPay (Cartago-only) does NOT continue in QuoteCenter —
-- every club uses PriceSmart Payments (business decision 2026-08-10), so
-- ps_globalpay_credenciales has no successor table.

-- ────────────────────────────────────────────────────────────────────────────
-- 10. Delivery routes (ex ps_rutas: composite string PK → surrogate id;
--     8 repeating price columns → route_prices rows)
-- ────────────────────────────────────────────────────────────────────────────

CREATE TABLE routes (
    id                    INT           NOT NULL AUTO_INCREMENT,
    club_id               INT           NOT NULL,
    code                  VARCHAR(10)   NOT NULL COMMENT 'ex llave',
    route_type_id         INT           NULL,
    name                  VARCHAR(200)  NOT NULL,
    truck_size            DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    requires_full_pallet  TINYINT(1)    NOT NULL DEFAULT 0,
    requires_half_pallet  TINYINT(1)    NOT NULL DEFAULT 0,
    is_active             TINYINT(1)    NOT NULL DEFAULT 1,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_routes_club_code (club_id, code),
    KEY idx_routes_club_active (club_id, is_active),
    CONSTRAINT fk_routes_club       FOREIGN KEY (club_id)       REFERENCES clubs (id),
    CONSTRAINT fk_routes_route_type FOREIGN KEY (route_type_id) REFERENCES route_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE route_prices (
    id          INT                                                    NOT NULL AUTO_INCREMENT,
    route_id    INT                                                    NOT NULL,
    unit_type   ENUM('TRIP','FULL_PALLET','HALF_PALLET','QUARTER_PALLET') NOT NULL,
    price_local DECIMAL(15,4)                                          NOT NULL DEFAULT 0,
    price_usd   DECIMAL(15,4)                                          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP                                              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP                                              NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_route_prices_route_unit (route_id, unit_type),
    CONSTRAINT fk_route_prices_route FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 11. Quotation core
-- ────────────────────────────────────────────────────────────────────────────

-- Header. currency_code + exchange_rate_to_usd are close-time SNAPSHOTS — the
-- single rate from which every USD figure is reconstructed (replaces all the
-- legacy per-row *_usd columns). Three identifiers, three columns:
-- quote_number (human/PDF), oms_order_id (OMS), gateway invoices on attempts.
-- Tax-invoice requirement lives ONLY on quotation_fiscal (3NF review fix).
CREATE TABLE quotations (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    quote_number              VARCHAR(20)   NOT NULL COMMENT 'app-generated business number (club prefix + sequence)',
    club_id                   INT           NOT NULL,
    channel_id                INT           NOT NULL COMMENT 'resolved by code at insert: STAFF or MEMBER (self-service)',
    created_by_user_id        BIGINT        NULL COMMENT 'staff creator; NULL for member self-service (member identity lives in quotation_customers). Service rule: channel STAFF requires a user',
    status_id                 INT           NOT NULL COMMENT 'resolved by code (OPEN) at insert — no magic default',
    quote_type_id             INT           NULL COMMENT 'set at close',
    cancel_reason_id          INT           NULL,
    currency_code             CHAR(3)       NOT NULL COMMENT 'SNAPSHOT of club local currency at creation',
    exchange_rate_to_usd      DECIMAL(15,6) NULL COMMENT 'SNAPSHOT from exchange_rates at close/OMS submit',
    purchase_order_reference  VARCHAR(50)   NULL COMMENT 'customer ODC/PO (ex orders.odc, was double!)',
    comments                  TEXT          NULL COMMENT 'header note printed on PDF',
    invoice_number            VARCHAR(30)   NULL COMMENT 'fiscal invoice reference after sale',
    oms_order_id              VARCHAR(30)   NULL COMMENT 'OMS orderId from submit (was misfiled as quotation_payment.quote_no)',
    oms_submitted_at          DATETIME      NULL,
    quoted_at                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_on                DATE          NULL COMMENT 'app default: quoted_at + 21 days',
    created_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotations_quote_number (quote_number),
    UNIQUE KEY uq_quotations_oms_order (oms_order_id),
    KEY idx_quotations_club_status (club_id, status_id),
    KEY idx_quotations_quoted_at (quoted_at),
    KEY idx_quotations_user (created_by_user_id),
    CONSTRAINT fk_quotations_club          FOREIGN KEY (club_id)            REFERENCES clubs (id),
    CONSTRAINT fk_quotations_channel       FOREIGN KEY (channel_id)         REFERENCES quotation_channels (id),
    CONSTRAINT fk_quotations_user          FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_quotations_status        FOREIGN KEY (status_id)          REFERENCES quotation_statuses (id),
    CONSTRAINT fk_quotations_quote_type    FOREIGN KEY (quote_type_id)      REFERENCES quote_types (id),
    CONSTRAINT fk_quotations_cancel_reason FOREIGN KEY (cancel_reason_id)   REFERENCES quotation_cancel_reasons (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- SNAPSHOT (1:1): member identity + contact AS QUOTED, captured from the
-- Business API merged with member_contact_overrides. Widened so payment links
-- and OMS submission never need a live API call.
CREATE TABLE quotation_customers (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id            BIGINT        NOT NULL,
    membership_number       VARCHAR(50)   NULL COMMENT 'NULL allowed for walk-in/prospect flows',
    first_name              VARCHAR(100)  NOT NULL,
    last_name               VARCHAR(100)  NULL,
    business_name           VARCHAR(255)  NULL,
    membership_type         VARCHAR(20)   NULL,
    membership_expires_on   DATE          NULL,
    email                   VARCHAR(150)  NULL,
    phone                   VARCHAR(30)   NULL,
    address_line1           VARCHAR(250)  NULL,
    city                    VARCHAR(100)  NULL,
    state_code              VARCHAR(3)    NULL,
    country_code            CHAR(2)       NOT NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_qcu_quotation (quotation_id),
    KEY idx_qcu_membership (membership_number),
    CONSTRAINT fk_qcu_quotation FOREIGN KEY (quotation_id) REFERENCES quotations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- SNAPSHOT (1:1): fiscal/FEL data for THIS quote. Geo/activity stored as
-- code+name snapshot so catalog renames never rewrite issued documents.
-- Single home of the tax-invoice requirement: FEL countries fill the geo
-- fields; TT/JM/BB rows carry only wants_tax_invoice (3NF review fix).
CREATE TABLE quotation_fiscal (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id            BIGINT        NOT NULL,
    fiscal_profile_id       BIGINT        NULL COMMENT 'provenance; NULL for ad-hoc fiscal data',
    membership_number       VARCHAR(50)   NULL,
    country_code            CHAR(2)       NOT NULL,
    document_type_id        INT           NULL,
    document_type_name      VARCHAR(80)   NULL COMMENT 'snapshot of name_en; OMS EI-IdType',
    document_number         VARCHAR(50)   NULL,
    document_validated      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'GoSocket result at quote time',
    business_name           VARCHAR(255)  NULL,
    address                 VARCHAR(500)  NULL,
    phone                   VARCHAR(30)   NULL,
    email                   VARCHAR(150)  NULL,
    nrc                     VARCHAR(30)   NULL COMMENT 'SV only',
    economic_activity_code  VARCHAR(20)   NULL,
    economic_activity_name  VARCHAR(255)  NULL COMMENT 'snapshot',
    city_code               VARCHAR(20)   NULL,
    city_name               VARCHAR(100)  NULL COMMENT 'snapshot',
    zone_code               VARCHAR(20)   NULL,
    zone_name               VARCHAR(100)  NULL COMMENT 'snapshot',
    neighborhood_code       VARCHAR(20)   NULL,
    neighborhood_name       VARCHAR(100)  NULL COMMENT 'snapshot',
    wants_tax_invoice       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'tax invoice requested for THIS quote (all countries)',
    wants_electronic_ticket TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'CR tiquete for THIS quote',
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_qfi_quotation (quotation_id),
    KEY idx_qfi_membership (membership_number),
    CONSTRAINT fk_qfi_quotation FOREIGN KEY (quotation_id)      REFERENCES quotations (id),
    CONSTRAINT fk_qfi_profile   FOREIGN KEY (fiscal_profile_id) REFERENCES member_fiscal_profiles (id) ON DELETE SET NULL,
    CONSTRAINT fk_qfi_doc_type  FOREIGN KEY (document_type_id)  REFERENCES fiscal_document_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Quotation line. Product columns are a point-in-time SNAPSHOT of the external
-- items API master (price/description/logistics at quote time — justified
-- duplication). quotation_item_product merged back in: it was a 1:1 vertical
-- partition with no normalization content. NOTE: delivery charge is NOT a
-- magic product line here — it lives in quotation_deliveries (3NF review fix).
CREATE TABLE quotation_items (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id        BIGINT        NOT NULL,
    product_id          VARCHAR(20)   NOT NULL COMMENT 'external items API key; snapshot reference, no local FK',
    description         VARCHAR(500)  NOT NULL COMMENT 'SNAPSHOT',
    qty                 DECIMAL(12,4) NOT NULL DEFAULT 0,
    unit_price          DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'ex rate; SNAPSHOT',
    sign_price          DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'POS sign price SNAPSHOT',
    line_amount         DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'qty * sign_price, persisted at close',
    discount_rate       DECIMAL(10,4) NULL COMMENT 'per-line discount percent at quote time',
    discount_amount     DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'per-line discount; quotation_totals.discount_amount is its close-time rollup',
    unit_cost           DECIMAL(15,4) NULL COMMENT 'ex cu_ea',
    unit_price_per_uom  DECIMAL(15,4) NULL,
    pack_level          DECIMAL(12,4) NULL COMMENT 'ex pl',
    units_per_pallet    DECIMAL(12,4) NULL COMMENT 'ex eaxpallet; pallet math',
    cube_volume         DECIMAL(12,4) NULL COMMENT 'volume (was DOUBLE); renamed — CUBE is reserved in MySQL 8',
    weight_each         DECIMAL(12,4) NULL COMMENT 'ex weight_ea',
    weight_total        DECIMAL(15,4) NULL COMMENT 'ex weight_result',
    pallet_qty          DECIMAL(12,4) NULL COMMENT 'ex palletxqty',
    on_hand             DECIMAL(12,2) NULL COMMENT 'stock SNAPSHOT',
    is_sold_by_weight   TINYINT(1)    NOT NULL DEFAULT 0,
    is_recipe           TINYINT(1)    NOT NULL DEFAULT 0,
    storage_type        VARCHAR(10)   NULL,
    picture_url         VARCHAR(500)  NULL COMMENT 'ex picture1',
    department_code     VARCHAR(10)   NULL,
    category_code       VARCHAR(10)   NULL,
    include_picture     TINYINT(1)    NOT NULL DEFAULT 0,
    has_price_variation TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'ex variacion',
    comments            VARCHAR(500)  NULL COMMENT 'ex icomments',
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotation_items_product (quotation_id, product_id),
    KEY idx_quotation_items_product (product_id),
    CONSTRAINT fk_qi_quotation FOREIGN KEY (quotation_id) REFERENCES quotations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Row-per-tax-type per line (3NF fix of the flattened tax_*/tax_ico/excent_*/
-- withholding_* repeating group). SNAPSHOT of tax rules applied at quote time.
-- Colombia: ICO + WITHHOLDING rows; exempt lines: EXEMPTION rows. New taxes
-- are rows, never DDL. CASCADE: lines are deleted while editing a live quote.
CREATE TABLE quotation_item_taxes (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_item_id BIGINT        NOT NULL,
    tax_type_id       INT           NOT NULL,
    tax_rate          DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT 'percent at quote time',
    base_amount       DECIMAL(15,4) NULL COMMENT 'taxable base (ex base/base_imp)',
    unit_factor       DECIMAL(15,6) NULL COMMENT 'per-unit tax amount (ex tax_factor); OMS price math',
    tax_amount        DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'line total for this tax; sign via tax_types.reduces_total',
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_qit_line_taxtype (quotation_item_id, tax_type_id),
    KEY idx_qit_tax_type (tax_type_id),
    CONSTRAINT fk_qit_item     FOREIGN KEY (quotation_item_id) REFERENCES quotation_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_qit_tax_type FOREIGN KEY (tax_type_id)       REFERENCES tax_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 1:1 persisted computed SNAPSHOT of header totals, frozen at close
-- (deliberately materialized temporal fact for immutability + cheap lists).
-- delivery_amount is the close-time rollup of quotation_deliveries.amount —
-- the delivery row is the source of truth; the service reconciles at close
-- (3NF review fix: single source, one materialized copy, no third home).
CREATE TABLE quotation_totals (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id          BIGINT        NOT NULL,
    apply_taxes           TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'ex aplicar_impuestos',
    is_exempt             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'ex excent flag',
    tax_rate              DECIMAL(10,4) NULL,
    gross_amount          DECIMAL(15,4) NOT NULL DEFAULT 0,
    discount_amount       DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'close-time rollup of line discount_amount',
    tax_total             DECIMAL(15,4) NOT NULL DEFAULT 0,
    ico_total             DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'rollup of line ICO (ex orders.total_ico, was double)',
    exempt_amount         DECIMAL(15,4) NOT NULL DEFAULT 0,
    apply_withholding     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'Colombia retención flag',
    withholding_total     DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'rollup of line WITHHOLDING rows',
    service_charge_rate   DECIMAL(10,4) NULL COMMENT 'TT/JM/BB',
    service_charge_amount DECIMAL(15,4) NULL,
    vat_charge_rate       DECIMAL(10,4) NULL COMMENT 'TT/JM/BB',
    vat_charge_amount     DECIMAL(15,4) NULL,
    delivery_amount       DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'close-time rollup of quotation_deliveries.amount',
    net_amount            DECIMAL(15,4) NOT NULL DEFAULT 0,
    total_units           DECIMAL(12,2) NULL COMMENT 'ex totalitems',
    total_weight          DECIMAL(12,2) NULL COMMENT 'ex totalweight',
    total_pallets         DECIMAL(10,2) NULL COMMENT 'ex totalpallets',
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotation_totals_quotation (quotation_id),
    CONSTRAINT fk_qt_quotation FOREIGN KEY (quotation_id) REFERENCES quotations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 1:1 payment summary — ONLY payment attributes (quote_type_id and the OMS
-- reference were evicted to quotations; hardcoded service_id dropped).
CREATE TABLE quotation_payments (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id       BIGINT        NOT NULL,
    payment_status_id  INT           NOT NULL COMMENT 'resolved by code (PENDING) at insert',
    payment_method_id  INT           NULL COMMENT 'country-scoped method row (carries the tender_key OMS needs)',
    payment_reference  VARCHAR(100)  NULL COMMENT 'check/transfer/receipt ref (ex payment_number)',
    paid_at            DATETIME      NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quotation_payments_quotation (quotation_id),
    CONSTRAINT fk_qp_quotation FOREIGN KEY (quotation_id)      REFERENCES quotations (id),
    CONSTRAINT fk_qp_status    FOREIGN KEY (payment_status_id) REFERENCES payment_statuses (id),
    CONSTRAINT fk_qp_method    FOREIGN KEY (payment_method_id) REFERENCES country_payment_methods (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- THE single append-only payment-attempt/link log (replaces payment_links,
-- payment_link_attempts and quotation_payment_attempts). gateway_invoice_id
-- nullable + uniqueness scoped per gateway so future gateways without a
-- PriceSmart-format invoice id fit without DDL changes.
CREATE TABLE payment_attempts (
    id                    BIGINT           NOT NULL AUTO_INCREMENT,
    quotation_id          BIGINT           NOT NULL,
    gateway_id            INT              NOT NULL,
    attempt_number        TINYINT UNSIGNED NOT NULL,
    gateway_invoice_id    VARCHAR(20)      NULL COMMENT 'callback correlation key (%08d%02d for PriceSmart Payments)',
    tender_key            INT              NULL COMMENT 'gateway tender snapshot',
    status_id             INT              NOT NULL COMMENT 'resolved by code (CREATED) at insert',
    gateway_status        VARCHAR(50)      NULL COMMENT 'raw gateway status, verbatim',
    authorization_token   VARCHAR(255)     NULL,
    payment_link_url      TEXT             NULL,
    amount                DECIMAL(15,4)    NULL COMMENT 'local-currency amount sent to gateway',
    currency_code         CHAR(3)          NULL,
    request_payload       JSON             NULL,
    response_payload      JSON             NULL,
    callback_payload      JSON             NULL,
    last_status_check_at  DATETIME         NULL,
    status_check_payload  JSON             NULL,
    status_check_error    TEXT             NULL,
    captured_at           DATETIME         NULL,
    capture_payload       JSON             NULL,
    capture_oms_order_id  VARCHAR(30)      NULL,
    expires_at            DATETIME         NULL,
    created_by_user_id    BIGINT           NULL,
    created_at            TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pa_gateway_invoice (gateway_id, gateway_invoice_id),
    UNIQUE KEY uq_pa_quotation_seq (quotation_id, gateway_id, attempt_number),
    KEY idx_pa_status (status_id),
    CONSTRAINT fk_pa_quotation FOREIGN KEY (quotation_id)       REFERENCES quotations (id),
    CONSTRAINT fk_pa_gateway   FOREIGN KEY (gateway_id)         REFERENCES payment_gateways (id),
    CONSTRAINT fk_pa_status    FOREIGN KEY (status_id)          REFERENCES payment_attempt_statuses (id),
    CONSTRAINT fk_pa_user      FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 12. Delivery operations
-- ────────────────────────────────────────────────────────────────────────────

-- Truck-load manifest (ex ps_delivery_log_cargue; DOUBLE id → BIGINT,
-- magic 2/3 status ints → ENUM, varchar route → FK).
CREATE TABLE delivery_loads (
    id                  BIGINT               NOT NULL AUTO_INCREMENT,
    club_id             INT                  NOT NULL,
    route_id            INT                  NOT NULL,
    status              ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
    created_by_user_id  BIGINT               NOT NULL,
    closed_at           DATETIME             NULL,
    sent_at             DATETIME             NULL,
    created_at          TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dl_club_status_route (club_id, status, route_id),
    CONSTRAINT fk_dl_club  FOREIGN KEY (club_id)            REFERENCES clubs (id),
    CONSTRAINT fk_dl_route FOREIGN KEY (route_id)           REFERENCES routes (id),
    CONSTRAINT fk_dl_user  FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One optional delivery per quotation (unifies quotation_delivery +
-- orders_delivery). route_name/unit_price/amount/window are SNAPSHOTS of what
-- was quoted; route_id stays a real FK for grouping into loads. Source of
-- truth for the delivery charge (quotation_totals.delivery_amount is its
-- close-time rollup).
CREATE TABLE quotation_deliveries (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    quotation_id      BIGINT        NOT NULL,
    route_id          INT           NULL,
    route_name        VARCHAR(200)  NULL COMMENT 'SNAPSHOT of routes.name',
    delivery_load_id  BIGINT        NULL,
    quantity          DECIMAL(12,4) NOT NULL DEFAULT 1 COMMENT 'billed units (pallets/trips)',
    unit_price        DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'SNAPSHOT of route_prices at quote time (local currency)',
    amount            DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT 'quantity * unit_price, SNAPSHOT',
    address           VARCHAR(500)  NULL,
    city_id           INT           NULL,
    delivery_date     DATE          NULL,
    window_start      TIME          NULL COMMENT 'chosen slot from Business API delivery windows',
    window_end        TIME          NULL,
    ring_code         VARCHAR(50)   NULL,
    box_code          VARCHAR(50)   NULL,
    pallet_count      DECIMAL(10,2) NULL,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_qd_quotation (quotation_id),
    KEY idx_qd_load (delivery_load_id),
    KEY idx_qd_route_unassigned (route_id, delivery_load_id, delivery_date),
    CONSTRAINT fk_qd_quotation FOREIGN KEY (quotation_id)     REFERENCES quotations (id),
    CONSTRAINT fk_qd_route     FOREIGN KEY (route_id)         REFERENCES routes (id),
    CONSTRAINT fk_qd_load      FOREIGN KEY (delivery_load_id) REFERENCES delivery_loads (id),
    CONSTRAINT fk_qd_city      FOREIGN KEY (city_id)          REFERENCES cities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ────────────────────────────────────────────────────────────────────────────
-- 13. Documents & notes
-- ────────────────────────────────────────────────────────────────────────────

-- One row per attached file (replaces orders.invoice_pic filename column and
-- orders.documents_folder_url folder pointer).
CREATE TABLE quotation_documents (
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

-- Timestamped, authored activity notes (follow-up calls before expiry, etc.)
-- Replaces the PK-less orders_calls. The single PDF header comment stays on
-- quotations.comments.
CREATE TABLE quotation_notes (
    id                  BIGINT                        NOT NULL AUTO_INCREMENT,
    quotation_id        BIGINT                        NOT NULL,
    note_type           ENUM('COMMENT','CALL')        NOT NULL DEFAULT 'COMMENT',
    body                TEXT                          NOT NULL,
    created_by_user_id  BIGINT                        NOT NULL,
    created_at          TIMESTAMP                     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_qn_quotation (quotation_id, created_at),
    CONSTRAINT fk_qn_quotation FOREIGN KEY (quotation_id)       REFERENCES quotations (id),
    CONSTRAINT fk_qn_user      FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- SEEDS — catalog value sets (services resolve rows BY CODE, never by id)
-- ============================================================================

INSERT INTO quotation_channels (code, name_en, name_es) VALUES
  ('STAFF',  'Club staff',          'Asesor de club'),
  ('MEMBER', 'Member self-service', 'Autoservicio del socio');

INSERT INTO quotation_statuses (code, name_en, name_es, sort_order) VALUES
  ('OPEN',        'Open',        'Abierta',      1),
  ('SUBMITTED',   'Submitted',   'Enviada',      2),
  ('CLOSED',      'Closed',      'Cerrada',      3),
  ('SENT_TO_OMS', 'Sent to OMS', 'Enviada a OMS',4),
  ('CANCELLED',   'Cancelled',   'Cancelada',    5),
  ('EXPIRED',     'Expired',     'Expirada',     6);

INSERT INTO payment_statuses (code, name_en, name_es, is_terminal) VALUES
  ('PENDING',   'Pending',   'Pendiente',  0),
  ('LINK_SENT', 'Link sent', 'Link enviado', 0),
  ('PAID',      'Paid',      'Pagada',     1),
  ('FAILED',    'Failed',    'Fallida',    0),
  ('REFUNDED',  'Refunded',  'Reembolsada',1);

INSERT INTO tax_types (code, name_en, name_es, reduces_total) VALUES
  ('IVA',         'IVA / VAT',            'IVA',                   0),
  ('ICO',         'ICO (Colombia)',       'Impuesto ICO',          0),
  ('EXEMPTION',   'Tax exemption',        'Exención',              1),
  ('WITHHOLDING', 'Withholding at source','Retención en la fuente',1);

INSERT INTO payment_gateways (code, name) VALUES
  ('PRICESMART_PAYMENTS', 'PriceSmart Payments Service');

INSERT INTO payment_attempt_statuses (code, name_en, name_es, is_terminal) VALUES
  ('CREATED',        'Created',        'Creado',           0),
  ('PENDING',        'Pending',        'Pendiente',        0),
  ('PAID',           'Paid',           'Pagado',           0),
  ('FAILED',         'Failed',         'Fallido',          1),
  ('EXPIRED',        'Expired',        'Expirado',         1),
  ('CANCELLED',      'Cancelled',      'Cancelado',        1),
  ('CAPTURED',       'Captured',       'Capturado',        1),
  ('CAPTURE_FAILED', 'Capture failed', 'Captura fallida',  1);

INSERT INTO document_types (code, name) VALUES
  ('INVOICE',          'Invoice'),
  ('INVOICE_IMAGE',    'Invoice image'),
  ('PURCHASE_ORDER',   'Purchase order (ODC)'),
  ('PROOF_OF_PAYMENT', 'Proof of payment'),
  ('OTHER',            'Other');

INSERT INTO roles (code, name, description) VALUES
  ('club_advisor',  'Club Advisor',  'Creates and manages quotations for their club(s)'),
  ('club_manager',  'Club Manager',  'Advisor permissions plus club-level oversight'),
  ('country_admin', 'Country Admin', 'Administers configuration for a country'),
  ('system_admin',  'System Admin',  'Full administration');

-- Seeds pending legacy value extraction (run against quotes_dqs_osys_dev):
--   quote_types              ← SELECT otid, description FROM orders_type;
--   quotation_cancel_reasons ← SELECT id, description FROM quotation_cancel_reasons;
--   oms_statuses             ← SELECT omsid, name_eng, name_esp FROM orders_oms_status;
--   payment_methods          ← SELECT pago_id, descripcion, tender_key, pais_iso2 FROM orders_pago;
--   fiscal_document_types    ← SELECT * FROM ps_fel;  (assign stable codes per row, preserve B2B-646 CR set)
--   countries / clubs / cities / zones / neighborhoods / economic_activities
--                            ← from countries, ps_tienda, cities, zones, neighborhoods, economic_activities
--   routes / route_prices    ← unpivot ps_rutas price columns
