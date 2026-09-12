-- ─────────────────────────────────────────────────────────────────────────────
-- Callejas purchase order (B2B-707)
--
-- The Callejas ODC a quotation was imported from. Legacy's orders.odc, which
-- the PDF importer writes and which Model_orders::getOrdersItemDataSort then
-- reads to decide how the quotation's lines are ordered.
--
-- BIGINT, not legacy's `double`. Every value in production is a 14-digit
-- integer — 24110230004769 and the like — and there are 884 of them, all in
-- clubs 6701-6704. A double happens to represent 14 digits exactly (they sit
-- well inside 2^53) so legacy gets away with it, but it is the wrong type for
-- an identifier and one digit more would start rounding.
--
-- NULL means the quotation did not come from a Callejas purchase order, which
-- is every quotation this application creates today. Legacy writes 0 for the
-- same thing and its own query tests `$ver == 0`, which in PHP is also true for
-- NULL — so both spellings already take the non-Callejas path there. One
-- spelling here.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE quotations
  ADD COLUMN odc BIGINT NULL COMMENT 'Callejas purchase order number; NULL = not a Callejas import'
  AFTER season_id;

CREATE INDEX idx_quotations_odc ON quotations (odc);
