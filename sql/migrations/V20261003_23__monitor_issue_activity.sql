CREATE TABLE IF NOT EXISTS monitor_issue_activity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  issue_id BIGINT NOT NULL,
  actor_admin_id BIGINT NOT NULL,
  actor_name VARCHAR(64) NOT NULL,
  activity_type VARCHAR(16) NOT NULL,
  comment_text VARCHAR(2000) DEFAULT NULL,
  previous_status VARCHAR(32) DEFAULT NULL,
  new_status VARCHAR(32) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_monitor_issue_activity_timeline (project_id, issue_id, id),
  CONSTRAINT fk_monitor_issue_activity_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_issue_activity_issue FOREIGN KEY (issue_id)
    REFERENCES monitor_issue (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Issue 评论与状态变更时间线';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_23', 'Issue comments and status activity timeline');
