-- Separate stable snapshot identity from snapshot content so retries retain the first captured value.
SET @monitor_delete_item_has_idempotency_key = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_data_deletion_item'
    AND column_name = 'idempotency_key'
);
SET @monitor_delete_item_ddl = IF(
  @monitor_delete_item_has_idempotency_key = 0,
  'ALTER TABLE monitor_data_deletion_item ADD COLUMN idempotency_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER item_type',
  'SELECT 1'
);
PREPARE monitor_delete_item_column_stmt FROM @monitor_delete_item_ddl;
EXECUTE monitor_delete_item_column_stmt;
DEALLOCATE PREPARE monitor_delete_item_column_stmt;

UPDATE monitor_data_deletion_item
SET idempotency_key = SHA2(CONCAT(item_type, ':', item_value), 256)
WHERE idempotency_key IS NULL;

ALTER TABLE monitor_data_deletion_item
  MODIFY COLUMN idempotency_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

SET @monitor_delete_item_has_key_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_data_deletion_item'
    AND index_name = 'uk_monitor_delete_item_key'
);
SET @monitor_delete_item_ddl = IF(
  @monitor_delete_item_has_key_index = 0,
  'ALTER TABLE monitor_data_deletion_item ADD UNIQUE KEY uk_monitor_delete_item_key (job_id, item_type, idempotency_key)',
  'SELECT 1'
);
PREPARE monitor_delete_item_key_index_stmt FROM @monitor_delete_item_ddl;
EXECUTE monitor_delete_item_key_index_stmt;
DEALLOCATE PREPARE monitor_delete_item_key_index_stmt;

SET @monitor_delete_item_has_legacy_index = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'monitor_data_deletion_item'
    AND index_name = 'uk_monitor_delete_item'
);
SET @monitor_delete_item_ddl = IF(
  @monitor_delete_item_has_legacy_index = 1,
  'ALTER TABLE monitor_data_deletion_item DROP INDEX uk_monitor_delete_item',
  'SELECT 1'
);
PREPARE monitor_delete_item_legacy_index_stmt FROM @monitor_delete_item_ddl;
EXECUTE monitor_delete_item_legacy_index_stmt;
DEALLOCATE PREPARE monitor_delete_item_legacy_index_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_05', 'monitor deletion item idempotency keys');
