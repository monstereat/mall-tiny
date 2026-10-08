SET @add_custom_sensitive_fields = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'custom_sensitive_fields') = 0,
  'ALTER TABLE monitor_project ADD COLUMN custom_sensitive_fields TEXT DEFAULT NULL AFTER scrub_ip_addresses',
  'SELECT 1'
);
PREPARE add_custom_sensitive_fields_stmt FROM @add_custom_sensitive_fields;
EXECUTE add_custom_sensitive_fields_stmt;
DEALLOCATE PREPARE add_custom_sensitive_fields_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_19', 'add project-level custom sensitive field names');
