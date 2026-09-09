-- ─────────────────────────────────────────────────────────────────────────────
-- quotation_cancel_reasons: adopt legacy's ids.
--
-- Legacy hardcodes the four closure options in the view with the values 1, 2, 4
-- and 5 — it skips 3 (views/orders/edit.php, the #opcion1 select). Our table was
-- seeded 1, 2, 3, 4, so two of the four reasons mean different things on each
-- side:
--
--     reason                          legacy   ours (before)
--     Existencias insuficientes            1       1
--     Rechazado por el tiempo              2       2
--     Cierre solicitado por el socio       4       3
--     Cerrado por precio                   5       4
--
-- The two systems write to different columns (orders.close_reason vs
-- quotations.cancel_reason_id), so nothing is corrupt today. What breaks is any
-- report that reads both: a QuoteCenter quote closed on price carries a 4, which
-- legacy reads as "requested by the member".
--
-- Existing rows move with the ids. At the time of writing exactly two
-- quotations carry a reason, both id 4 (price declined), and they become 5 —
-- their meaning is preserved, which is the whole point of doing this in SQL
-- rather than renumbering the catalog alone.
--
-- Order matters because quotations.cancel_reason_id has a foreign key
-- (fk_q_cancel_reason): the target id has to exist before any row points at it,
-- and the vacated id can only be deleted once nothing references it.
--
-- Descriptions stay in English. They are the catalog's own identity, not what
-- the operator reads: legacy has no such table and renders each option through
-- get_phrase('closure_op*'), so the frontend does the equivalent and labels
-- these by id through i18n. Putting Spanish here would show Spanish to the
-- English locales.
--
-- Idempotent, and deliberately so: the remap runs only while reason 3 still
-- exists, which is what the old scheme looks like. Without that guard a second
-- run would move the rows that legitimately hold 4 today.
-- ─────────────────────────────────────────────────────────────────────────────

DROP PROCEDURE IF EXISTS dqs_adopt_legacy_close_reason_ids;

DELIMITER //
CREATE PROCEDURE dqs_adopt_legacy_close_reason_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM quotation_cancel_reasons WHERE id = 3) THEN

        -- 5 has to exist before the price-declined rows can point at it.
        INSERT INTO quotation_cancel_reasons (id, description)
        SELECT 5, 'Price declined'
        WHERE NOT EXISTS (SELECT 1 FROM quotation_cancel_reasons WHERE id = 5);

        UPDATE quotations SET cancel_reason_id = 5 WHERE cancel_reason_id = 4;

        -- 4 is now free to take the meaning legacy gives it.
        UPDATE quotation_cancel_reasons SET description = 'Requested by customer' WHERE id = 4;

        UPDATE quotations SET cancel_reason_id = 4 WHERE cancel_reason_id = 3;

        -- Nothing references 3 any more.
        DELETE FROM quotation_cancel_reasons WHERE id = 3;

    END IF;
END //
DELIMITER ;

CALL dqs_adopt_legacy_close_reason_ids();

DROP PROCEDURE IF EXISTS dqs_adopt_legacy_close_reason_ids;
