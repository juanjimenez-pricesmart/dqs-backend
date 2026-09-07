-- ─────────────────────────────────────────────────────────────────────────────
-- QuoteCenter catalogs: the tables we take over from the legacy application.
--
-- Clubs, delivery routes and their tariffs, fiscal document types, payment
-- methods and exchange rates stop being read out of ps_tienda, ps_rutas,
-- ps_fel, orders_pago and ps_tasa_cambio, and become ours.
--
-- These definitions are migration_quotecenter_schema.sql's, applied at last.
-- Fourteen of that file's tables already exist here — countries, cities, zones,
-- neighborhoods, economic_activities and the quotation core — so this is the
-- rest of a design that was written and half-landed, not a new one.
--
-- Same database as everything else on purpose. The catalogs join quotation
-- tables in two places (DeliveryLogRepository, PriceSmartPaymentService), and a
-- separate database or engine turns those joins into application code for no
-- gain. Ownership does not come from where the rows live: the legacy
-- application does not know these tables exist.
--
-- Idempotent: safe to re-run.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── countries: three columns the applied version never got ──────────────────
-- `code` here already holds the ISO2 (AW, CO, CR…), so no column is needed for
-- that. Only what we are about to populate is added; the rest of the blueprint's
-- country columns stay unbuilt until something reads them.

DROP PROCEDURE IF EXISTS dqs_add_column_if_missing;
DELIMITER //
CREATE PROCEDURE dqs_add_column_if_missing(
    IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = p_table
                     AND column_name = p_column) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_ddl);
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END //
DELIMITER ;

-- Whether stored line amounts already contain the tax. From the legacy
-- ps_tienda.impuesto_operacion: '+' meant total = subtotal + tax, so amounts
-- EXCLUDE it; anything else meant total = subtotal - tax. Read by the OMS
-- payload builder.
CALL dqs_add_column_if_missing('countries', 'price_includes_tax',
     "TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'ex ps_tienda.impuesto_operacion'");

CALL dqs_add_column_if_missing('countries', 'default_language',
     "CHAR(2) NOT NULL DEFAULT 'es' COMMENT 'ex ps_tienda.idioma'");

-- IVA, VAT, ITBMS… Its own column: the legacy overloaded impuesto_operacion to
-- pick a tax label as well as the arithmetic, and got Colombia wrong doing it.
CALL dqs_add_column_if_missing('countries', 'tax_name', "VARCHAR(20) NULL");

DROP PROCEDURE IF EXISTS dqs_add_column_if_missing;

-- ── clubs (ex ps_tienda) ─────────────────────────────────────────────────────
-- club_number is the operational code the rest of the world uses (6101, 6410)
-- and is deliberately not the primary key: the items API, OMS and the frontend
-- all key off it, so it stays a stable business identifier.

CREATE TABLE IF NOT EXISTS clubs (
    id                      INT           NOT NULL AUTO_INCREMENT,
    club_number             INT           NOT NULL COMMENT 'ex ps_tienda_id',
    country_id              INT           NOT NULL,
    name                    VARCHAR(100)  NOT NULL,
    address                 VARCHAR(250)  NULL,
    phone                   VARCHAR(70)   NULL,
    latitude                DECIMAL(9,6)  NULL,
    longitude               DECIMAL(9,6)  NULL,
    tax_registration_number VARCHAR(30)   NULL COMMENT 'printed on quotes (ex ps_tienda.nit)',
    timezone                VARCHAR(64)   NOT NULL DEFAULT 'America/Guatemala',
    is_active               TINYINT(1)    NOT NULL DEFAULT 1,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_clubs_club_number (club_number),
    KEY idx_clubs_country_active (country_id, is_active),
    CONSTRAINT fk_clubs_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── route types (ex ps_tipos_ruta) ───────────────────────────────────────────

CREATE TABLE IF NOT EXISTS route_types (
    id          INT           NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(200)  NULL,
    is_active   TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_route_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── routes (ex ps_rutas) ─────────────────────────────────────────────────────
-- `code` is the legacy `llave`, unique only within a club — and the value
-- quotation_delivery.route_id stores, so it has to keep its exact form.

CREATE TABLE IF NOT EXISTS routes (
    id            INT           NOT NULL AUTO_INCREMENT,
    club_id       INT           NOT NULL,
    code          VARCHAR(10)   NOT NULL COMMENT 'ex llave, e.g. "6101 01"',
    route_type_id INT           NULL,
    name          VARCHAR(200)  NOT NULL,
    truck_size    DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    is_active     TINYINT(1)    NOT NULL DEFAULT 1,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_routes_club_code (club_id, code),
    KEY idx_routes_club_active (club_id, is_active),
    CONSTRAINT fk_routes_club       FOREIGN KEY (club_id)       REFERENCES clubs (id),
    CONSTRAINT fk_routes_route_type FOREIGN KEY (route_type_id) REFERENCES route_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── route prices (ex four columns on ps_rutas) ───────────────────────────────
-- The legacy row held a column per tier — pallet_local, halfpallet_local,
-- `14pallet_local`, `14pallet_usd` — so adding a tier meant an ALTER and every
-- reader had to know the column names. One row per tier instead.
--
-- minimum_quantity is pallet_required / halfpallet_required. The 3NF draft had
-- turned those into booleans on the route, which throws the numbers away: they
-- are counts (4 full pallets, 15 half) and the delivery panel prints them. The
-- minimum belongs to the tier, which is why the quarter-pallet tier has none.

CREATE TABLE IF NOT EXISTS route_prices (
    id               INT NOT NULL AUTO_INCREMENT,
    route_id         INT NOT NULL,
    unit_type        ENUM('TRIP','FULL_PALLET','HALF_PALLET','QUARTER_PALLET') NOT NULL,
    price_local      DECIMAL(15,4) NOT NULL DEFAULT 0,
    price_usd        DECIMAL(15,4) NOT NULL DEFAULT 0,
    minimum_quantity INT           NULL COMMENT 'smallest billable quantity at this tier',
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_route_prices_route_unit (route_id, unit_type),
    CONSTRAINT fk_route_prices_route FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── fiscal document types (ex ps_fel) ────────────────────────────────────────
-- The id is the legacy felid, inserted explicitly rather than generated:
-- quotation_fiscal.document_type holds the felid, not the code, so generated
-- ids would orphan every fiscal record already saved.
--
-- `code` is the stable identity the per-type number validation keys off — the
-- old nombre_en. name_es is a label and free to change; nothing matches on it.

CREATE TABLE IF NOT EXISTS fiscal_document_types (
    id               INT           NOT NULL COMMENT 'ex ps_fel.felid — persisted in quotation_fiscal',
    country_id       INT           NOT NULL,
    code             VARCHAR(30)   NOT NULL COMMENT 'NIT, CUI, PHYSICAL, LEGAL, DIMEX, NITE…',
    name_en          VARCHAR(80)   NOT NULL,
    name_es          VARCHAR(80)   NOT NULL,
    input_mask       VARCHAR(150)  NULL COMMENT 'ex ps_fel.formato',
    validation_regex VARCHAR(150)  NULL,
    is_active        TINYINT(1)    NOT NULL DEFAULT 1,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_fdt_country_code (country_id, code),
    KEY idx_fdt_country_active (country_id, is_active),
    CONSTRAINT fk_fdt_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── payment methods (ex orders_pago) ─────────────────────────────────────────
-- orders_pago repeated the method name on every country row, which is how two
-- spellings of the same method came to exist. The concept is defined once.

CREATE TABLE IF NOT EXISTS payment_method_types (
    id         INT           NOT NULL AUTO_INCREMENT,
    code       VARCHAR(50)   NOT NULL COMMENT 'VISA, EFECTIVO, PAYMENT_LINK…',
    name       VARCHAR(100)  NOT NULL,
    is_active  TINYINT(1)    NOT NULL DEFAULT 1,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_method_types_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- tender_key is why this table exists rather than a flag on the type: it feeds
-- the OMS payload and genuinely varies — cash alone has five keys across the
-- region. It is also the value quotation_payment.payment_method_id stores.
CREATE TABLE IF NOT EXISTS country_payment_methods (
    id             INT        NOT NULL AUTO_INCREMENT,
    country_id     INT        NOT NULL,
    method_type_id INT        NOT NULL,
    tender_key     INT        NOT NULL COMMENT 'POS/OMS tender key — persisted on quotations',
    is_active      TINYINT(1) NOT NULL DEFAULT 1,
    sort_order     INT        NOT NULL DEFAULT 0 COMMENT '0 everywhere: the name decides, as legacy did',
    created_at     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpm_country_method (country_id, method_type_id),
    KEY idx_cpm_country_active (country_id, is_active),
    CONSTRAINT fk_cpm_country     FOREIGN KEY (country_id)     REFERENCES countries (id),
    CONSTRAINT fk_cpm_method_type FOREIGN KEY (method_type_id) REFERENCES payment_method_types (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── exchange rates (ex ps_tasa_cambio) ───────────────────────────────────────
-- One rate per country per day. The legacy table also scoped rates per store
-- and every caller ignored that, so the dimension is gone. A quote snapshots
-- the rate it used, so this is only ever consulted for the current one.
--
-- created_by_user_id is a real foreign key here. It could not be one in the
-- Azure design, where users lived in another database.

CREATE TABLE IF NOT EXISTS exchange_rates (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    country_id         INT           NOT NULL,
    rate               DECIMAL(15,6) NOT NULL COMMENT 'local units per 1 USD',
    effective_date     DATE          NOT NULL,
    source             VARCHAR(100)  NULL,
    created_by_user_id INT           NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_exchange_rates_country_date (country_id, effective_date),
    CONSTRAINT fk_exchange_rates_country FOREIGN KEY (country_id) REFERENCES countries (id),
    CONSTRAINT fk_exchange_rates_user    FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
