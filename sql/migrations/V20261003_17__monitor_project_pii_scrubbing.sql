SET @add_scrub_emails = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'scrub_emails') = 0,
  'ALTER TABLE monitor_project ADD COLUMN scrub_emails TINYINT(1) NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1'
);
PREPARE add_scrub_emails_stmt FROM @add_scrub_emails;
EXECUTE add_scrub_emails_stmt;
DEALLOCATE PREPARE add_scrub_emails_stmt;

SET @add_scrub_credit_cards = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'monitor_project' AND column_name = 'scrub_credit_cards') = 0,
  'ALTER TABLE monitor_project ADD COLUMN scrub_credit_cards TINYINT(1) NOT NULL DEFAULT 0 AFTER scrub_emails',
  'SELECT 1'
);
PREPARE add_scrub_credit_cards_stmt FROM @add_scrub_credit_cards;
EXECUTE add_scrub_credit_cards_stmt;
DEALLOCATE PREPARE add_scrub_credit_cards_stmt;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_17', 'add project-level PII scrubbing settings');
