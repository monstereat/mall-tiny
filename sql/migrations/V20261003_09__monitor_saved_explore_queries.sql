CREATE TABLE IF NOT EXISTS monitor_saved_explore_query (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  criteria_json JSON NOT NULL,
  created_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_saved_explore_project_name (project_id, name),
  KEY idx_monitor_saved_explore_creator (project_id, created_by),
  CONSTRAINT fk_monitor_saved_explore_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目共享的 Explore 查询';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_09', 'project-scoped saved Explore queries');
