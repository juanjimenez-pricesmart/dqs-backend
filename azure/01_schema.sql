-- ─────────────────────────────────────────────────────────────────────────────
-- QuoteCenter catalogs — Azure SQL (SQL Server)
--
-- Ported from migration_quotecenter_schema.sql, which designed these tables for
-- MySQL and was never applied. The design is that file's; only the dialect is
-- new. See README.md for the translation table and the ownership rationale.
--
-- Idempotent: safe to re-run. Each object is created only if absent.
--
-- Every text column is NVARCHAR, including the fixed-length ISO codes. CHAR
-- would be the tighter type for iso2/iso3/currency_code, but Hibernate maps a
-- String to a variable-length column and schema validation rejects CHAR at
-- startup. Uniformity is worth more here than three saved bytes, and CHAR's
-- space padding is a comparison bug waiting to happen anyway.
--
-- Timestamps are DATETIMEOFFSET, not DATETIME2. They are written with
-- SYSUTCDATETIME(), so the stored offset is +00:00 and the type says so. The
-- entities map them to java.time.Instant, and Hibernate's SQL Server dialect
-- requires DATETIMEOFFSET for that — a DATETIME2 column fails schema validation
-- at startup. Mapping to LocalDateTime instead would have silenced it while
-- leaving a Java type that claims to have no timezone holding a UTC value.
-- ─────────────────────────────────────────────────────────────────────────────

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- ── countries ────────────────────────────────────────────────────────────────
-- Parent of every other catalog here. `price_includes_tax` is the old
-- ps_tienda.impuesto_operacion, moved up: it never varied by club, only by
-- country, and the OMS payload builder reads it.

IF OBJECT_ID(N'dbo.countries', N'U') IS NULL
CREATE TABLE dbo.countries (
    id                            INT            IDENTITY(1,1) NOT NULL,
    iso2                          NVARCHAR(2)        NOT NULL,
    iso3                          NVARCHAR(3)        NOT NULL,
    name                          NVARCHAR(100)  NOT NULL,
    currency_code                 NVARCHAR(3)        NOT NULL,   -- ISO 4217 local currency; USD implicit system-wide
    currency_symbol               NVARCHAR(10)   NULL,
    currency_name                 NVARCHAR(50)   NULL,
    default_language              NVARCHAR(2)        NOT NULL CONSTRAINT DF_countries_lang DEFAULT ('es'),
    tax_name                      NVARCHAR(20)   NULL,       -- IVA, VAT, ITBMS…
    tax_id_label                  NVARCHAR(10)   NULL,       -- NIT, RUC, RTN (ex ps_tienda.tributo_siglas)
    price_includes_tax            BIT            NOT NULL CONSTRAINT DF_countries_pit DEFAULT (0),
    weight_unit                   NVARCHAR(10)   NULL,
    volume_unit                   NVARCHAR(10)   NULL,
    min_quote_amount_usd          DECIMAL(15,4)  NOT NULL CONSTRAINT DF_countries_minusd DEFAULT (50.0000),
    min_quote_amount_local        DECIMAL(15,4)  NULL,       -- business-supplied, not a conversion
    transfer_notice_amount_usd    DECIMAL(15,4)  NOT NULL CONSTRAINT DF_countries_tnusd DEFAULT (250.0000),
    transfer_notice_amount_local  DECIMAL(15,4)  NULL,
    is_active                     BIT            NOT NULL CONSTRAINT DF_countries_active DEFAULT (1),
    created_at                    DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_countries_created DEFAULT (SYSUTCDATETIME()),
    updated_at                    DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_countries_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_countries      PRIMARY KEY (id),
    CONSTRAINT UQ_countries_iso2 UNIQUE (iso2),
    CONSTRAINT UQ_countries_iso3 UNIQUE (iso3)
);
GO

-- ── clubs ────────────────────────────────────────────────────────────────────
-- `club_number` is the operational code the rest of the world uses (6101, 6410).
-- It is deliberately NOT the primary key: it is a business identifier, and the
-- items API, OMS and the frontend all key off it, so it must stay stable and
-- unique while `id` stays a surrogate.

IF OBJECT_ID(N'dbo.clubs', N'U') IS NULL
CREATE TABLE dbo.clubs (
    id                      INT            IDENTITY(1,1) NOT NULL,
    club_number             INT            NOT NULL,   -- ex ps_tienda_id
    country_id              INT            NOT NULL,
    name                    NVARCHAR(100)  NOT NULL,
    address                 NVARCHAR(250)  NULL,
    phone                   NVARCHAR(70)   NULL,       -- may hold several numbers/extensions
    latitude                DECIMAL(9,6)   NULL,
    longitude               DECIMAL(9,6)   NULL,
    tax_registration_number NVARCHAR(30)   NULL,       -- printed on quotes (ex ps_tienda.nit)
    timezone                NVARCHAR(64)   NOT NULL CONSTRAINT DF_clubs_tz DEFAULT ('America/Guatemala'),
    is_active               BIT            NOT NULL CONSTRAINT DF_clubs_active DEFAULT (1),
    created_at              DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_clubs_created DEFAULT (SYSUTCDATETIME()),
    updated_at              DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_clubs_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_clubs             PRIMARY KEY (id),
    CONSTRAINT UQ_clubs_club_number UNIQUE (club_number),
    CONSTRAINT FK_clubs_country     FOREIGN KEY (country_id) REFERENCES dbo.countries (id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_clubs_country_active' AND object_id = OBJECT_ID(N'dbo.clubs'))
    CREATE INDEX IX_clubs_country_active ON dbo.clubs (country_id, is_active);
GO

-- ── route types ──────────────────────────────────────────────────────────────

IF OBJECT_ID(N'dbo.route_types', N'U') IS NULL
CREATE TABLE dbo.route_types (
    id          INT            IDENTITY(1,1) NOT NULL,
    code        NVARCHAR(50)   NOT NULL,
    name        NVARCHAR(100)  NOT NULL,
    description NVARCHAR(200)  NULL,
    is_active   BIT            NOT NULL CONSTRAINT DF_route_types_active DEFAULT (1),
    created_at  DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_route_types_created DEFAULT (SYSUTCDATETIME()),
    updated_at  DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_route_types_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_route_types      PRIMARY KEY (id),
    CONSTRAINT UQ_route_types_code UNIQUE (code)
);
GO

-- ── routes ───────────────────────────────────────────────────────────────────
-- `code` is the legacy `llave`, unique only within a club — hence the composite
-- unique key rather than a global one.

IF OBJECT_ID(N'dbo.routes', N'U') IS NULL
CREATE TABLE dbo.routes (
    id                   INT            IDENTITY(1,1) NOT NULL,
    club_id              INT            NOT NULL,
    code                 NVARCHAR(10)   NOT NULL,   -- ex llave
    route_type_id        INT            NULL,
    name                 NVARCHAR(200)  NOT NULL,
    truck_size           DECIMAL(10,2)  NOT NULL CONSTRAINT DF_routes_truck DEFAULT (0.00),
    is_active            BIT            NOT NULL CONSTRAINT DF_routes_active DEFAULT (1),
    created_at           DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_routes_created DEFAULT (SYSUTCDATETIME()),
    updated_at           DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_routes_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_routes            PRIMARY KEY (id),
    CONSTRAINT UQ_routes_club_code  UNIQUE (club_id, code),
    CONSTRAINT FK_routes_club       FOREIGN KEY (club_id)       REFERENCES dbo.clubs (id),
    CONSTRAINT FK_routes_route_type FOREIGN KEY (route_type_id) REFERENCES dbo.route_types (id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_routes_club_active' AND object_id = OBJECT_ID(N'dbo.routes'))
    CREATE INDEX IX_routes_club_active ON dbo.routes (club_id, is_active);
GO

-- ── route prices ─────────────────────────────────────────────────────────────
-- The legacy row carried a column per tariff (pallet_local, halfpallet_local,
-- `14pallet_local`, `14pallet_usd`), which is why adding a tier meant an ALTER.
-- One row per unit type instead. MySQL's ENUM becomes a CHECK constraint.

IF OBJECT_ID(N'dbo.route_prices', N'U') IS NULL
CREATE TABLE dbo.route_prices (
    id          INT            IDENTITY(1,1) NOT NULL,
    route_id    INT            NOT NULL,
    unit_type   NVARCHAR(20)   NOT NULL,
    price_local DECIMAL(15,4)  NOT NULL CONSTRAINT DF_route_prices_local DEFAULT (0),
    price_usd   DECIMAL(15,4)  NOT NULL CONSTRAINT DF_route_prices_usd DEFAULT (0),
    -- Smallest billable quantity at this tier. The legacy pallet_required and
    -- halfpallet_required columns, which the 3NF draft had turned into booleans
    -- on the route — they are counts, not flags: 4 full pallets, 15 half. The
    -- delivery panel prints the number. It belongs to the tier, not the route,
    -- which is also why the quarter-pallet tier simply has none.
    minimum_quantity INT       NULL,
    created_at  DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_route_prices_created DEFAULT (SYSUTCDATETIME()),
    updated_at  DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_route_prices_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_route_prices           PRIMARY KEY (id),
    CONSTRAINT UQ_route_prices_route_unit UNIQUE (route_id, unit_type),
    CONSTRAINT CK_route_prices_minimum    CHECK (minimum_quantity IS NULL OR minimum_quantity > 0),
    CONSTRAINT CK_route_prices_unit_type CHECK (unit_type IN (N'TRIP', N'FULL_PALLET', N'HALF_PALLET', N'QUARTER_PALLET')),
    CONSTRAINT FK_route_prices_route     FOREIGN KEY (route_id) REFERENCES dbo.routes (id) ON DELETE CASCADE
);
GO

-- ── fiscal document types ────────────────────────────────────────────────────
-- `code` is the stable identity the per-type number validation keys off — the
-- old ps_fel.nombre_en. `name_es` is a label and free to change; nothing should
-- match on it.

IF OBJECT_ID(N'dbo.fiscal_document_types', N'U') IS NULL
CREATE TABLE dbo.fiscal_document_types (
    id               INT            IDENTITY(1,1) NOT NULL,
    country_id       INT            NOT NULL,
    code             NVARCHAR(30)   NOT NULL,   -- NIT, CUI, PHYSICAL, LEGAL, DIMEX, NITE…
    name_en          NVARCHAR(80)   NOT NULL,   -- display + OMS EI-IdType value
    name_es          NVARCHAR(80)   NOT NULL,
    min_length       SMALLINT       NULL,
    max_length       SMALLINT       NULL,
    input_mask       NVARCHAR(150)  NULL,       -- ex ps_fel.formato
    validation_regex NVARCHAR(150)  NULL,       -- ex ps_fel.formato2
    is_active        BIT            NOT NULL CONSTRAINT DF_fdt_active DEFAULT (1),
    created_at       DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_fdt_created DEFAULT (SYSUTCDATETIME()),
    updated_at       DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_fdt_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_fiscal_document_types  PRIMARY KEY (id),
    CONSTRAINT UQ_fdt_country_code       UNIQUE (country_id, code),
    CONSTRAINT CK_fdt_length_order       CHECK (min_length IS NULL OR max_length IS NULL OR min_length <= max_length),
    CONSTRAINT FK_fdt_country            FOREIGN KEY (country_id) REFERENCES dbo.countries (id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_fdt_country_active' AND object_id = OBJECT_ID(N'dbo.fiscal_document_types'))
    CREATE INDEX IX_fdt_country_active ON dbo.fiscal_document_types (country_id, is_active);
GO

-- ── payment methods ──────────────────────────────────────────────────────────
-- The concept is defined once; only what genuinely varies by country lives in
-- the per-country row. In `orders_pago` the name was repeated per country,
-- which is how "Efectivo" and "EFECTIVO" ended up both existing.

IF OBJECT_ID(N'dbo.payment_method_types', N'U') IS NULL
CREATE TABLE dbo.payment_method_types (
    id         INT            IDENTITY(1,1) NOT NULL,
    code       NVARCHAR(50)   NOT NULL,   -- VISA, EFECTIVO, PAYMENT_LINK…
    name       NVARCHAR(100)  NOT NULL,
    is_active  BIT            NOT NULL CONSTRAINT DF_pmt_active DEFAULT (1),
    created_at DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_pmt_created DEFAULT (SYSUTCDATETIME()),
    updated_at DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_pmt_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_payment_method_types      PRIMARY KEY (id),
    CONSTRAINT UQ_payment_method_types_code UNIQUE (code)
);
GO

-- tender_key genuinely varies by country — cash alone has five across the
-- region — which is the reason this table exists rather than a flag on the type.
IF OBJECT_ID(N'dbo.country_payment_methods', N'U') IS NULL
CREATE TABLE dbo.country_payment_methods (
    id             INT           IDENTITY(1,1) NOT NULL,
    country_id     INT           NOT NULL,
    method_type_id INT           NOT NULL,
    tender_key     INT           NOT NULL,   -- POS/OMS tender key
    is_active      BIT           NOT NULL CONSTRAINT DF_cpm_active DEFAULT (1),
    sort_order     INT           NOT NULL CONSTRAINT DF_cpm_sort DEFAULT (0),
    created_at     DATETIMEOFFSET(3)  NOT NULL CONSTRAINT DF_cpm_created DEFAULT (SYSUTCDATETIME()),
    updated_at     DATETIMEOFFSET(3)  NOT NULL CONSTRAINT DF_cpm_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_country_payment_methods PRIMARY KEY (id),
    CONSTRAINT UQ_cpm_country_method      UNIQUE (country_id, method_type_id),
    CONSTRAINT FK_cpm_country             FOREIGN KEY (country_id)     REFERENCES dbo.countries (id),
    CONSTRAINT FK_cpm_method_type         FOREIGN KEY (method_type_id) REFERENCES dbo.payment_method_types (id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_cpm_country_active' AND object_id = OBJECT_ID(N'dbo.country_payment_methods'))
    CREATE INDEX IX_cpm_country_active ON dbo.country_payment_methods (country_id, is_active);
GO

-- ── exchange rates ───────────────────────────────────────────────────────────
-- Append-only, one row per country per day. The legacy table scoped rates per
-- store and every caller ignored it, so that dimension is dropped. Quotes
-- snapshot the rate they used; this is not their source of truth after the fact.
--
-- created_by_user_id points at `users`, which lives in the MySQL database. It
-- stays an unconstrained id: Azure SQL cannot enforce a foreign key across
-- databases, and declaring one that does not hold would be worse than the gap.

IF OBJECT_ID(N'dbo.exchange_rates', N'U') IS NULL
CREATE TABLE dbo.exchange_rates (
    id                 BIGINT         IDENTITY(1,1) NOT NULL,
    country_id         INT            NOT NULL,
    rate               DECIMAL(15,6)  NOT NULL,   -- local units per 1 USD
    effective_date     DATE           NOT NULL,
    source             NVARCHAR(100)  NULL,
    created_by_user_id BIGINT         NULL,       -- MySQL users.id; no FK possible
    created_at         DATETIMEOFFSET(3)   NOT NULL CONSTRAINT DF_fx_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT PK_exchange_rates            PRIMARY KEY (id),
    CONSTRAINT UQ_exchange_rates_country_date UNIQUE (country_id, effective_date),
    CONSTRAINT CK_exchange_rates_positive   CHECK (rate > 0),
    CONSTRAINT FK_exchange_rates_country    FOREIGN KEY (country_id) REFERENCES dbo.countries (id)
);
GO

-- ── updated_at triggers ──────────────────────────────────────────────────────
-- T-SQL has no ON UPDATE CURRENT_TIMESTAMP. Doing this in the database rather
-- than in Hibernate keeps it correct for the import script and for any manual
-- correction — precisely the moments when a stale timestamp misleads.

DECLARE @t SYSNAME, @sql NVARCHAR(MAX);
DECLARE c CURSOR LOCAL FAST_FORWARD FOR
    SELECT name FROM (VALUES
        (N'countries'), (N'clubs'), (N'route_types'), (N'routes'), (N'route_prices'),
        (N'fiscal_document_types'), (N'payment_method_types'), (N'country_payment_methods')
    ) AS x(name);
OPEN c;
FETCH NEXT FROM c INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF OBJECT_ID(N'dbo.TR_' + @t + N'_updated_at', N'TR') IS NULL
    BEGIN
        SET @sql = N'CREATE TRIGGER dbo.TR_' + @t + N'_updated_at ON dbo.' + QUOTENAME(@t) + N'
                     AFTER UPDATE AS
                     BEGIN
                         SET NOCOUNT ON;
                         IF NOT UPDATE(updated_at)
                            UPDATE t SET updated_at = SYSUTCDATETIME()
                            FROM dbo.' + QUOTENAME(@t) + N' t
                            INNER JOIN inserted i ON i.id = t.id;
                     END';
        EXEC sp_executesql @sql;
    END
    FETCH NEXT FROM c INTO @t;
END
CLOSE c; DEALLOCATE c;
GO
