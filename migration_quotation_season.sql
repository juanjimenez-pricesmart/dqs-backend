-- ─────────────────────────────────────────────────────────────────────────────
-- Temporadas (B2B-707)
--
-- The season a quotation belongs to. Legacy's orders.temporada_id, a plain
-- reference into ps_temporada written by orders/temporadaupdate.
--
-- No foreign key. ps_temporada belongs to the application we are replacing —
-- its tid is a `double`, it is latin1, and we do not own its lifecycle — so the
-- column holds the id the way legacy does and the catalog is read through
-- NativeQueries. Adding a constraint across that boundary would make a delete
-- on their side a failure on ours.
--
-- NULL means "no season", which is what a quotation created here starts as.
-- Legacy uses 0 for the same thing in 366,499 of its 372,000 orders and NULL in
-- another 5,347 — two spellings of nothing, which is why this column allows
-- only one.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE quotations
  ADD COLUMN season_id INT NULL COMMENT 'ps_temporada.tid; NULL = no season'
  AFTER cancel_reason_id;

CREATE INDEX idx_quotations_season ON quotations (season_id);
