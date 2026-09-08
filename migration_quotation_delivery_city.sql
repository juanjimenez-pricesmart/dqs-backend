-- ─────────────────────────────────────────────────────────────────────────────
-- quotation_delivery: the city the panel has always sent and the table never
-- had a place for.
--
-- DeliveryPanel sends city_id and city_name on every save. saveDelivery never
-- mentions either, and quotation_delivery has no columns for them, so they
-- were dropped silently: the operator picks a city, the save succeeds, and the
-- field is empty when the quote is reopened. Legacy keeps the same value in
-- orders_delivery.ciudadid.
--
-- Two columns, not one, following what this schema already does for every
-- quote-time fact: the code identifies the row, the name is a snapshot so the
-- quote keeps showing what was quoted if the city is later renamed or
-- deactivated. quotation_fiscal.city_code / city_name are the same pair for
-- the fiscal address, and route_id / route_name on this very table are that
-- pair for the route.
--
-- city_code is VARCHAR, matching route_id beside it rather than the integer
-- the id happens to be: the delivery panel resolves the selection as a string
-- and the catalog serves it as one, on both sides of the catalog flag.
--
-- No foreign key to delivery_cities. This is a snapshot of a choice, and the
-- catalog is still readable from the legacy tables depending on
-- quotecenter.catalogs.own-tables — a constraint would fail in exactly the
-- environments that have not switched yet.
--
-- Apply before deploying the code: ddl-auto is `validate`, so the entity's
-- column list has to match the live table or the application does not start.
--
-- Idempotent: safe to re-run.
-- ─────────────────────────────────────────────────────────────────────────────

DROP PROCEDURE IF EXISTS dqs_add_delivery_city_column;

DELIMITER //
CREATE PROCEDURE dqs_add_delivery_city_column(
    IN p_column VARCHAR(64),
    IN p_ddl    VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = 'quotation_delivery'
          AND column_name  = p_column
    ) THEN
        SET @s = CONCAT('ALTER TABLE quotation_delivery ADD COLUMN `', p_column, '` ', p_ddl);
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END //
DELIMITER ;

CALL dqs_add_delivery_city_column('city_code',
     'VARCHAR(20) DEFAULT NULL COMMENT ''delivery_cities.id / legacy ps_delivery_ciudades.idco''');

CALL dqs_add_delivery_city_column('city_name',
     'VARCHAR(100) DEFAULT NULL COMMENT ''snapshot at quote time''');

DROP PROCEDURE IF EXISTS dqs_add_delivery_city_column;
