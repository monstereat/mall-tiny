SET @add_scrub_phone_numbers = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'scrub_phone_numbers') = 0,
  'ALTER TABLE monitor_project ADD COLUMN scrub_phone_numbers TINYINT(1) NOT NULL DEFAULT 0 AFTER scrub_ip_addresses',
  'SELECT 1');
PREPARE add_scrub_phone_numbers_stmt FROM @add_scrub_phone_numbers;
EXECUTE add_scrub_phone_numbers_stmt;
DEALLOCATE PREPARE add_scrub_phone_numbers_stmt;

SET @add_scrub_chinese_id_numbers = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'scrub_chinese_id_numbers') = 0,
  'ALTER TABLE monitor_project ADD COLUMN scrub_chinese_id_numbers TINYINT(1) NOT NULL DEFAULT 0 AFTER scrub_phone_numbers',
  'SELECT 1');
PREPARE add_scrub_chinese_id_numbers_stmt FROM @add_scrub_chinese_id_numbers;
EXECUTE add_scrub_chinese_id_numbers_stmt;
DEALLOCATE PREPARE add_scrub_chinese_id_numbers_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_22', 'add project-level phone and Chinese ID scrubbing settings');
