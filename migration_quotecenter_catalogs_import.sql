-- ─────────────────────────────────────────────────────────────────────────────
-- One-time copy of the catalogs out of the legacy tables.
--
-- Source and target are in the same database, so this is plain INSERT..SELECT.
-- The Azure version of this needed a Java generator and a 900 KB file of
-- statements because the two ends were different engines; none of that is
-- necessary here, and the mapping is readable as SQL instead of as a program
-- that writes SQL.
--
-- Idempotent: every statement is INSERT IGNORE or an UPDATE, so re-running
-- changes nothing. Run after migration_quotecenter_catalogs.sql.
--
-- The legacy tables are a mix of character sets — some utf8mb4_0900_ai_ci,
-- some latin1 — and ours are utf8mb4_unicode_ci. So every join on a text column
-- goes through CONVERT(... USING utf8mb4) COLLATE utf8mb4_unicode_ci. A plain
-- COLLATE is not enough: applied to a latin1 column MySQL rejects it outright
-- ("COLLATION 'utf8mb4_unicode_ci' is not valid for CHARACTER SET 'latin1'").
-- ─────────────────────────────────────────────────────────────────────────────

-- ── countries: the three columns just added ─────────────────────────────────
-- price_includes_tax comes from ps_tienda.impuesto_operacion, whose meaning is
-- in OrdersService::calculateCorrectTotals: '+' means total = subtotal + tax,
-- so amounts EXCLUDE tax; anything else, including empty, means they include
-- it. This reproduces current behaviour, which is not necessarily anyone's
-- intent — see the note on Panama in the parity document.
UPDATE countries c
JOIN (
    SELECT pais_iso2, MIN(impuesto_operacion) AS op, MIN(idioma) AS idioma
    FROM ps_tienda GROUP BY pais_iso2
) t ON CONVERT(t.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci = c.code
SET c.price_includes_tax = IF(t.op = '+', 0, 1),
    c.default_language   = COALESCE(NULLIF(t.idioma, ''), 'es');

-- Tax names are ours to state; the legacy overloaded impuesto_operacion for
-- this and labelled Colombia as VAT.
UPDATE countries SET tax_name = CASE code
    WHEN 'CO' THEN 'IVA'   WHEN 'CR' THEN 'IVA'   WHEN 'GT' THEN 'IVA'
    WHEN 'NI' THEN 'IVA'   WHEN 'SV' THEN 'IVA'   WHEN 'DO' THEN 'ITBIS'
    WHEN 'HN' THEN 'ISV'   WHEN 'PA' THEN 'ITBMS' WHEN 'AW' THEN 'BBO'
    WHEN 'BB' THEN 'VAT'   WHEN 'JM' THEN 'GCT'   WHEN 'TT' THEN 'VAT'
    WHEN 'VI' THEN 'VAT'   ELSE tax_name END
WHERE tax_name IS NULL;

-- ── clubs ───────────────────────────────────────────────────────────────────
INSERT IGNORE INTO clubs (club_number, country_id, name, latitude, longitude, is_active)
SELECT t.ps_tienda_id, c.id, t.nombre, t.latitud, t.longitud, IF(t.status = 'A', 1, 0)
FROM ps_tienda t
JOIN countries c ON c.code = CONVERT(t.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci;

-- ── route types ─────────────────────────────────────────────────────────────
INSERT IGNORE INTO route_types (code, name, description, is_active)
SELECT tr.codigo, tr.nombre, tr.descripcion, IF(tr.status = 'A', 1, 0)
FROM ps_tipos_ruta tr;

-- ── routes ──────────────────────────────────────────────────────────────────
-- The join to clubs drops routes whose club is not in ps_tienda: 9 of them,
-- belonging to clubs 6308 and 8703, which do not exist. Legacy orphans.
INSERT IGNORE INTO routes (club_id, code, route_type_id, name, truck_size, is_active)
SELECT cl.id, r.llave,
       (SELECT rt.id FROM route_types rt
         WHERE rt.code = CONVERT(tr.codigo USING utf8mb4) COLLATE utf8mb4_unicode_ci),
       r.descripcion, r.truck_size, IF(r.status = 'A', 1, 0)
FROM ps_rutas r
JOIN clubs cl ON cl.club_number = r.ps_tienda_id
LEFT JOIN ps_tipos_ruta tr ON tr.id = r.tipo_ruta_id;

-- ── route prices: one row per tier that carried a value ─────────────────────
INSERT IGNORE INTO route_prices (route_id, unit_type, price_local, price_usd, minimum_quantity)
SELECT ro.id, 'FULL_PALLET', r.pallet_local, 0, NULLIF(r.pallet_required, 0)
FROM ps_rutas r
JOIN clubs cl ON cl.club_number = r.ps_tienda_id
JOIN routes ro ON ro.club_id = cl.id AND ro.code = CONVERT(r.llave USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE r.pallet_local IS NOT NULL;

INSERT IGNORE INTO route_prices (route_id, unit_type, price_local, price_usd, minimum_quantity)
SELECT ro.id, 'HALF_PALLET', r.halfpallet_local, 0, NULLIF(r.halfpallet_required, 0)
FROM ps_rutas r
JOIN clubs cl ON cl.club_number = r.ps_tienda_id
JOIN routes ro ON ro.club_id = cl.id AND ro.code = CONVERT(r.llave USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE r.halfpallet_local IS NOT NULL;

-- The legacy row carries no minimum for the quarter-pallet tier.
INSERT IGNORE INTO route_prices (route_id, unit_type, price_local, price_usd, minimum_quantity)
SELECT ro.id, 'QUARTER_PALLET', COALESCE(r.`14pallet_local`, 0), COALESCE(r.`14pallet_usd`, 0), NULL
FROM ps_rutas r
JOIN clubs cl ON cl.club_number = r.ps_tienda_id
JOIN routes ro ON ro.club_id = cl.id AND ro.code = CONVERT(r.llave USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE r.`14pallet_local` IS NOT NULL OR r.`14pallet_usd` IS NOT NULL;

-- ── fiscal document types ───────────────────────────────────────────────────
-- id = felid, explicitly: quotation_fiscal.document_type stores it.
-- Deduplicated on (country, nombre_en) — ps_fel has JM 'Passport' twice — with
-- the lowest felid winning, which is the row the legacy dropdown showed first.
INSERT IGNORE INTO fiscal_document_types (id, country_id, code, name_en, name_es, input_mask)
SELECT MIN(f.felid), c.id, f.nombre_en, f.nombre_en, MIN(f.nombre_es), MIN(f.formato)
FROM ps_fel f
JOIN countries c ON c.code = CONVERT(f.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE f.nombre_en IS NOT NULL
GROUP BY c.id, f.nombre_en;

-- ── payment methods ─────────────────────────────────────────────────────────
-- The code is derived from the description, upper-cased and trimmed, so two
-- spellings of the same method collapse into one type.
INSERT IGNORE INTO payment_method_types (code, name)
SELECT DISTINCT UPPER(TRIM(p.descripcion)), TRIM(p.descripcion)
FROM orders_pago p;

-- sort_order stays 0: legacy ordered the dropdown alphabetically and the reader
-- falls back to the name, so this reproduces it. Set a non-zero value to move a
-- row without touching the query.
INSERT IGNORE INTO country_payment_methods (country_id, method_type_id, tender_key, sort_order)
SELECT c.id, m.id, MIN(p.tender_key), 0
FROM orders_pago p
JOIN countries c ON c.code = CONVERT(p.pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci
JOIN payment_method_types m
  ON m.code = CONVERT(UPPER(TRIM(p.descripcion)) USING utf8mb4) COLLATE utf8mb4_unicode_ci
GROUP BY c.id, m.id;

-- ── exchange rates ──────────────────────────────────────────────────────────
-- One per country per day; where per-store values disagreed on the same day the
-- most recent row wins. Nothing reads history — a quote snapshots its own rate —
-- but discarding years of it is irreversible and it costs only disk.
INSERT IGNORE INTO exchange_rates (country_id, rate, effective_date, source)
SELECT c.id, x.rate, x.d, 'legacy ps_tasa_cambio'
FROM (
    SELECT ps_pais_iso2, DATE(ps_tasa_cambio_fecha) AS d, ps_tasa_cambio_tipocambio AS rate,
           ROW_NUMBER() OVER (PARTITION BY ps_pais_iso2, DATE(ps_tasa_cambio_fecha)
                              ORDER BY ps_tasa_cambio_id DESC) AS rn
    FROM ps_tasa_cambio WHERE ps_tasa_cambio_tipocambio > 0
) x
JOIN countries c ON c.code = CONVERT(x.ps_pais_iso2 USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE x.rn = 1;
