-- Durable preview/execute/audit state for cross-store monitor data deletion.
CREATE TABLE IF NOT EXISTS monitor_data_deletion_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  project_key VARCHAR(128) NOT NULL,
  user_id VARCHAR(128) DEFAULT NULL,
  range_start DATETIME(3) NOT NULL,
  range_end DATETIME(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  stage VARCHAR(48) NOT NULL,
  preview_token CHAR(64) NOT NULL,
  preview_counts_json JSON NOT NULL,
  cursor_value VARCHAR(512) DEFAULT NULL,
  deleted_counts_json JSON DEFAULT NULL,
  requested_by BIGINT NOT NULL,
  error_message VARCHAR(1024) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) DEFAULT NULL,
  finished_at DATETIME(3) DEFAULT NULL,
  lease_until DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_delete_preview_token (preview_token),
  KEY idx_monitor_delete_status_update (status, update_time),
  KEY idx_monitor_delete_project_time (project_id, range_start, range_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控数据删除预览与可续跑任务';

CREATE TABLE IF NOT EXISTS monitor_data_deletion_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  item_value VARCHAR(2048) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_delete_item (job_id, item_type, item_value(512)),
  KEY idx_monitor_delete_item_batch (job_id, item_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控删除任务的跨存储幂等重建快照';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_04', 'monitor data deletion jobs');
