SET @add_scrub_ip_addresses = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'scrub_ip_addresses') = 0,
  'ALTER TABLE monitor_project ADD COLUMN scrub_ip_addresses TINYINT(1) NOT NULL DEFAULT 0 AFTER scrub_credit_cards',
  'SELECT 1'
);
PREPARE add_scrub_ip_addresses_stmt FROM @add_scrub_ip_addresses;
EXECUTE add_scrub_ip_addresses_stmt;
DEALLOCATE PREPARE add_scrub_ip_addresses_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_18', 'add project-level IP address scrubbing setting');
