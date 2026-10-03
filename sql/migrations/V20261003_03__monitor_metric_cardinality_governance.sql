CREATE TABLE IF NOT EXISTS monitor_metric_cardinality_project (
  project_id BIGINT NOT NULL,
  metric_count INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (project_id),
  CONSTRAINT fk_monitor_metric_cardinality_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目近 90 天活跃 Metrics 名称计数';

CREATE TABLE IF NOT EXISTS monitor_metric_cardinality_metric (
  project_id BIGINT NOT NULL,
  metric_name VARCHAR(128) NOT NULL,
  dimension_key_count INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (project_id, metric_name),
  KEY idx_monitor_metric_cardinality_metric_expiry (last_seen),
  CONSTRAINT fk_monitor_metric_cardinality_metric_project FOREIGN KEY (project_id)
    REFERENCES monitor_metric_cardinality_project (project_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目 Metrics 指标名与维度键基数计数';

CREATE TABLE IF NOT EXISTS monitor_metric_cardinality_dimension (
  project_id BIGINT NOT NULL,
  metric_name VARCHAR(128) NOT NULL,
  dimension_key VARCHAR(64) NOT NULL,
  distinct_value_count INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (project_id, metric_name, dimension_key),
  KEY idx_monitor_metric_cardinality_dimension_expiry (last_seen),
  CONSTRAINT fk_monitor_metric_cardinality_dimension_metric FOREIGN KEY (project_id, metric_name)
    REFERENCES monitor_metric_cardinality_metric (project_id, metric_name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Metrics 自定义维度键与 90 天 distinct 值计数';

CREATE TABLE IF NOT EXISTS monitor_metric_cardinality_value (
  project_id BIGINT NOT NULL,
  metric_name VARCHAR(128) NOT NULL,
  dimension_key VARCHAR(64) NOT NULL,
  value_hash BINARY(32) NOT NULL,
  last_seen DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (project_id, metric_name, dimension_key, value_hash),
  KEY idx_monitor_metric_cardinality_value_expiry (last_seen),
  CONSTRAINT fk_monitor_metric_cardinality_value_dimension
    FOREIGN KEY (project_id, metric_name, dimension_key)
    REFERENCES monitor_metric_cardinality_dimension (project_id, metric_name, dimension_key) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Metrics 维度值 SHA-256 台账，不存储原始标签值';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_03', 'monitor metric dimension cardinality governance');
