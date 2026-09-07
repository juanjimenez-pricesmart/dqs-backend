-- ─────────────────────────────────────────────────────────────────────────────
-- quotation_delivery: three columns the code has always used and no migration
-- ever declared.
--
-- migration_delivery.sql created the table with 15 columns. The application
-- has been reading and writing three more:
--
--   route_name   DeliveryService.saveDelivery writes it on every save
--   pallets      DeliveryService.saveDelivery writes it; DeliveryLogRepository reads it
--   logcargueid  DeliveryLogRepository reads it and links deliveries to a load
--
-- They exist in the development database, added by hand at some point without
-- a migration to record it. Any environment built from the migration files
-- alone does not have them, and would fail on the first delivery save.
--
-- This became blocking with the move to JPA: `spring.jpa.hibernate.ddl-auto`
-- is `validate`, so the entity's column list has to match the live table
-- exactly or the application does not start at all. The mapping was taken from
-- SHOW COLUMNS against the development database; this file makes the migration
-- history say the same thing.
--
-- Safe to re-run: each column is added only if it is absent, so the database
-- that already has them is left alone.
-- ─────────────────────────────────────────────────────────────────────────────

DROP PROCEDURE IF EXISTS dqs_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE dqs_add_column_if_missing(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl    VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        SET @stmt = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_ddl);
        PREPARE s FROM @stmt;
        EXECUTE s;
        DEALLOCATE PREPARE s;
    END IF;
END //
DELIMITER ;

-- Snapshot of the route description at the time of quoting. Denormalised on
-- purpose: the quote has to keep showing what was quoted even if the route is
-- renamed or deactivated afterwards.
CALL dqs_add_column_if_missing('quotation_delivery', 'route_name',
     'VARCHAR(200) DEFAULT NULL AFTER route_id');

-- Billed units for the delivery. DECIMAL because half and quarter pallets are
-- charged, which is also why the route tariff has three tiers.
CALL dqs_add_column_if_missing('quotation_delivery', 'pallets',
     'DECIMAL(10,2) DEFAULT NULL');

-- Load this delivery was dispatched on: ps_delivery_log_cargue.logcargueid.
-- DOUBLE rather than BIGINT to match the legacy column it points at; not a
-- declared foreign key, since that table belongs to the legacy application.
CALL dqs_add_column_if_missing('quotation_delivery', 'logcargueid',
     'DOUBLE DEFAULT NULL');

DROP PROCEDURE IF EXISTS dqs_add_column_if_missing;
