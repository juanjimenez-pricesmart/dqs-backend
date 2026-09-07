-- ─────────────────────────────────────────────────────────────────────────────
-- quotation_fiscal: the two checkboxes the fiscal form has always discarded.
--
-- StepFiscal sends generate_fiscal and generate_tiquete_electronico on every
-- save. The table has no columns for them and FiscalService never mentions
-- them, so they were dropped silently: the operator ticks a box, the save
-- succeeds, and the box is empty again next time.
--
-- They are not cosmetic. generate_tiquete_electronico is Costa Rica's
-- anonymous electronic ticket — it hides and clears the whole identification
-- block — and generate_fiscal is the tax-invoice request for Trinidad, Jamaica
-- and Barbados. Both decide what the invoicing provider is asked for.
--
-- TINYINT(1) to match document_validated on the same table. Note that the
-- driver reports TINYINT(1) as BIT, so the entity maps these as Boolean while
-- the API keeps carrying them as 0/1.
--
-- Idempotent: safe to re-run.
-- ─────────────────────────────────────────────────────────────────────────────

DROP PROCEDURE IF EXISTS dqs_add_fiscal_flag;
DELIMITER //
CREATE PROCEDURE dqs_add_fiscal_flag(IN p_column VARCHAR(64), IN p_comment VARCHAR(200))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'quotation_fiscal'
                     AND column_name = p_column) THEN
        SET @s = CONCAT('ALTER TABLE quotation_fiscal ADD COLUMN `', p_column,
                        '` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''', p_comment, '''');
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END //
DELIMITER ;

CALL dqs_add_fiscal_flag('generate_fiscal',
     'Tax invoice requested — Trinidad, Jamaica, Barbados');
CALL dqs_add_fiscal_flag('generate_tiquete_electronico',
     'Anonymous electronic ticket — Costa Rica; suppresses the identification block');

DROP PROCEDURE IF EXISTS dqs_add_fiscal_flag;
