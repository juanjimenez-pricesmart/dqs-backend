-- ─────────────────────────────────────────────────────────────────────────────
-- delivery_cities (ex ps_delivery_ciudades)
--
-- The delivery panel has had a city selector since it landed — DeliveryPanel
-- .tsx calls GET /api/v1/deliveries/cities — and nothing served it. The list
-- came back as an error, the component's `cities.length > 0` guard hid the
-- field, and the operator never saw that a city could be chosen at all.
--
-- The catalog exists only in the legacy database, so it joins the same seam as
-- routes, document types and payment methods: read through CatalogSource, from
-- ps_delivery_ciudades or from here depending on quotecenter.catalogs.own-tables.
--
-- IMPORTANT — apply this before deploying the code that goes with it. The
-- DeliveryCity entity is mapped whatever the flag says, and
-- spring.jpa.hibernate.ddl-auto is `validate`: an environment without this
-- table will not start, not even one still reading the legacy catalogs.
--
-- The legacy idco is kept as the id rather than generated. quotation_delivery
-- stores the chosen city, so a surrogate key would orphan every delivery
-- already saved and would change meaning when the flag flips — the same
-- reasoning as fiscal_document_types.felid and routes.code.
--
-- No unique key on the name: the legacy table has repeated city names within a
-- country and this is a copy, not a cleanup.
--
-- Same database as everything else, so the import is plain INSERT..SELECT. The
-- join goes through CONVERT(... USING utf8mb4) COLLATE because the legacy
-- tables are a mix of character sets and a bare COLLATE fails outright on a
-- latin1 column.
--
-- Idempotent: safe to re-run. Run after migration_quotecenter_catalogs.sql.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS delivery_cities (
    id         INT           NOT NULL COMMENT 'ex ps_delivery_ciudades.idco — persisted on the delivery row',
    country_id INT           NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    is_active  TINYINT(1)    NOT NULL DEFAULT 1,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dc_country_active (country_id, is_active),
    KEY idx_dc_name (name),
    CONSTRAINT fk_dc_country FOREIGN KEY (country_id) REFERENCES countries (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── import ──────────────────────────────────────────────────────────────────
-- status is legacy's active flag, filtered on by Model_orders::getciudades.
-- Rows whose pais_iso2 matches no country are dropped by the join, which is
-- the same thing the endpoint does today.
INSERT IGNORE INTO delivery_cities (id, country_id, name, is_active)
SELECT dc.idco,
       c.id,
       TRIM(dc.nombre),
       IF(dc.status = 1, 1, 0)
FROM ps_delivery_ciudades dc
JOIN countries c
  ON c.code = CONVERT(dc.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE dc.nombre IS NOT NULL AND TRIM(dc.nombre) <> '';
