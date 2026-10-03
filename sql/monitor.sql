CREATE TABLE IF NOT EXISTS monitor_tenant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_key (tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控租户';

CREATE TABLE IF NOT EXISTS monitor_tenant_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  role_key VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  permissions_json JSON NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_tenant_role_key (tenant_id, role_key),
  UNIQUE KEY uk_monitor_tenant_role_tenant_id (tenant_id, id),
  CONSTRAINT fk_monitor_tenant_role_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户自定义组织权限角色';

CREATE TABLE IF NOT EXISTS monitor_tenant_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  custom_role_id BIGINT DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_admin (tenant_id, admin_id),
  KEY idx_admin_tenant (admin_id, tenant_id),
  KEY idx_monitor_tenant_member_custom_role (custom_role_id),
  CONSTRAINT fk_monitor_tenant_member_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_tenant_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_tenant_member_custom_role FOREIGN KEY (tenant_id, custom_role_id)
    REFERENCES monitor_tenant_role (tenant_id, id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控租户成员';

CREATE TABLE IF NOT EXISTS monitor_team (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  team_key VARCHAR(64) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_team_key (tenant_id, team_key),
  UNIQUE KEY uk_team_tenant_id (tenant_id, id),
  KEY idx_team_tenant (tenant_id),
  CONSTRAINT fk_monitor_team_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控团队';

CREATE TABLE IF NOT EXISTS monitor_team_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_team_admin (team_id, admin_id),
  KEY idx_monitor_team_member_admin (admin_id, tenant_id),
  CONSTRAINT fk_monitor_team_member_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_team_member_team FOREIGN KEY (tenant_id, team_id)
    REFERENCES monitor_team (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_team_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队成员及团队项目访问角色';

CREATE TABLE IF NOT EXISTS monitor_scim_token (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_by BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_used_at DATETIME(3) DEFAULT NULL,
  revoked_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_scim_token_hash (token_hash),
  KEY idx_monitor_scim_token_tenant (tenant_id, revoked_at),
  CONSTRAINT fk_monitor_scim_token_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_token_creator FOREIGN KEY (created_by)
    REFERENCES ums_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM 租户级凭据，仅保存 SHA-256 摘要';

CREATE TABLE IF NOT EXISTS monitor_scim_user (
  scim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  external_id VARCHAR(256) DEFAULT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scim_id),
  UNIQUE KEY uk_monitor_scim_user_tenant_admin (tenant_id, admin_id),
  UNIQUE KEY uk_monitor_scim_user_tenant_external (tenant_id, external_id),
  KEY idx_monitor_scim_user_tenant_active (tenant_id, active),
  CONSTRAINT fk_monitor_scim_user_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_user_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM 用户与 mall-tiny 管理员映射';

CREATE TABLE IF NOT EXISTS monitor_scim_group (
  scim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  external_id VARCHAR(256) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scim_id),
  UNIQUE KEY uk_monitor_scim_group_tenant_team (tenant_id, team_id),
  UNIQUE KEY uk_monitor_scim_group_tenant_external (tenant_id, external_id),
  CONSTRAINT fk_monitor_scim_group_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_scim_group_team FOREIGN KEY (tenant_id, team_id)
    REFERENCES monitor_team (tenant_id, id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SCIM Group 与监控团队映射';

CREATE TABLE IF NOT EXISTS monitor_tenant_saml_config (
  tenant_id BIGINT NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  metadata_xml MEDIUMTEXT NOT NULL,
  email_attribute VARCHAR(128) NOT NULL DEFAULT 'email',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id),
  UNIQUE KEY uk_monitor_saml_tenant_key (tenant_key),
  CONSTRAINT fk_monitor_saml_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户 SAML 2.0 服务提供方配置';

CREATE TABLE IF NOT EXISTS monitor_tenant_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  actor_admin_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  detail_json JSON NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_monitor_tenant_audit_time (tenant_id, id),
  KEY idx_monitor_tenant_audit_actor (actor_admin_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控组织审计日志';

INSERT INTO monitor_tenant (name, tenant_key, status)
VALUES ('Default Tenant', 'default', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

INSERT INTO monitor_team (tenant_id, name, team_key, is_default)
SELECT id, 'Default Team', 'default', 1 FROM monitor_tenant WHERE tenant_key = 'default'
ON DUPLICATE KEY UPDATE name = VALUES(name), is_default = 1;

CREATE TABLE IF NOT EXISTS monitor_project (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  ingest_key_hash CHAR(64) NOT NULL,
  release_key_hash CHAR(64) NOT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'web',
  owner_id BIGINT DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_key (project_key),
  UNIQUE KEY uk_ingest_key_hash (ingest_key_hash),
  KEY idx_project_tenant_team (tenant_id, team_id),
  CONSTRAINT fk_monitor_project_team FOREIGN KEY (tenant_id, team_id)
    REFERENCES monitor_team (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控项目';

CREATE TABLE IF NOT EXISTS monitor_cron (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  slug VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL,
  schedule_type VARCHAR(16) NOT NULL,
  schedule VARCHAR(128) NOT NULL,
  timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
  checkin_margin_seconds INT NOT NULL DEFAULT 60,
  max_runtime_seconds INT NOT NULL DEFAULT 1800,
  failure_threshold INT NOT NULL DEFAULT 1,
  recovery_threshold INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  health_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  consecutive_failures INT NOT NULL DEFAULT 0,
  consecutive_successes INT NOT NULL DEFAULT 0,
  last_checkin_at DATETIME(3) DEFAULT NULL,
  last_checkin_status VARCHAR(16) DEFAULT NULL,
  next_checkin_at DATETIME(3) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_cron_project_slug (project_id, slug),
  KEY idx_monitor_cron_due (status, next_checkin_at),
  CONSTRAINT fk_monitor_cron_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务监控';

CREATE TABLE IF NOT EXISTS monitor_cron_checkin (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cron_id BIGINT NOT NULL,
  checkin_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  environment VARCHAR(64) NOT NULL DEFAULT 'production',
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  duration_ms BIGINT DEFAULT NULL,
  message VARCHAR(512) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_cron_checkin (cron_id, checkin_id),
  KEY idx_monitor_cron_checkin_history (cron_id, id),
  KEY idx_monitor_cron_checkin_open (cron_id, status, started_at),
  CONSTRAINT fk_monitor_cron_checkin_monitor FOREIGN KEY (cron_id)
    REFERENCES monitor_cron (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务 Check-in 历史';

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

CREATE TABLE IF NOT EXISTS monitor_uptime_check (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  slug VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL,
  url VARCHAR(2048) NOT NULL,
  method VARCHAR(8) NOT NULL DEFAULT 'GET',
  interval_seconds INT NOT NULL DEFAULT 60,
  timeout_ms INT NOT NULL DEFAULT 5000,
  expected_status_code SMALLINT NOT NULL DEFAULT 200,
  failure_threshold INT NOT NULL DEFAULT 1,
  recovery_threshold INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  current_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  consecutive_failures INT NOT NULL DEFAULT 0,
  consecutive_successes INT NOT NULL DEFAULT 0,
  checked_at DATETIME(3) DEFAULT NULL,
  last_status_code SMALLINT DEFAULT NULL,
  last_duration_ms BIGINT DEFAULT NULL,
  last_error VARCHAR(512) DEFAULT NULL,
  next_check_at DATETIME(3) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_uptime_project_slug (project_id, slug),
  KEY idx_monitor_uptime_due (status, next_check_at),
  KEY idx_monitor_uptime_project (project_id, id),
  CONSTRAINT fk_monitor_uptime_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 可用性监控';

CREATE TABLE IF NOT EXISTS monitor_uptime_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  uptime_check_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  response_status SMALLINT DEFAULT NULL,
  duration_ms BIGINT NOT NULL,
  message VARCHAR(512) DEFAULT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_monitor_uptime_history_check (uptime_check_id, id),
  KEY idx_monitor_uptime_history_checked_at (checked_at),
  CONSTRAINT fk_monitor_uptime_history_check FOREIGN KEY (uptime_check_id)
    REFERENCES monitor_uptime_check (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 可用性检查历史';

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
    REFERENCES monitor_project (id) ON DELETE CASCADE
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


CREATE TABLE IF NOT EXISTS monitor_project_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_admin (project_id, admin_id),
  KEY idx_admin_project (admin_id, project_id),
  CONSTRAINT fk_monitor_member_project FOREIGN KEY (project_id)
    REFERENCES monitor_project (id) ON DELETE CASCADE,
  CONSTRAINT fk_monitor_member_admin FOREIGN KEY (admin_id)
    REFERENCES ums_admin (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控项目成员';

CREATE TABLE IF NOT EXISTS monitor_schema_migration (
  version VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控平台数据库迁移记录';

CREATE TABLE IF NOT EXISTS monitor_release (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  version VARCHAR(128) NOT NULL,
  environment VARCHAR(32) NOT NULL DEFAULT 'production',
  git_commit VARCHAR(64) DEFAULT NULL,
  branch_name VARCHAR(128) DEFAULT NULL,
  source_map_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  build_time DATETIME DEFAULT NULL,
  deploy_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_release (project_id, version, environment),
  KEY idx_project_deploy_time (project_id, deploy_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布版本';

CREATE TABLE IF NOT EXISTS monitor_issue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  title VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'unresolved',
  event_count BIGINT NOT NULL DEFAULT 1,
  affected_users BIGINT NOT NULL DEFAULT 0,
  first_seen DATETIME(3) NOT NULL,
  last_seen DATETIME(3) NOT NULL,
  latest_release VARCHAR(128) DEFAULT NULL,
  regressed_at DATETIME(3) DEFAULT NULL,
  resolved_at DATETIME(3) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_fingerprint (project_id, fingerprint),
  KEY idx_project_last_seen (project_id, last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常聚合 Issue';

CREATE TABLE IF NOT EXISTS monitor_alert_notification_route (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  webhook_url VARCHAR(1024) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_alert_route_tenant_name (tenant_id, name),
  CONSTRAINT fk_monitor_alert_route_tenant FOREIGN KEY (tenant_id)
    REFERENCES monitor_tenant (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户共享的告警通知路由';

CREATE TABLE IF NOT EXISTS monitor_alert_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  metric VARCHAR(64) NOT NULL,
  operator VARCHAR(16) NOT NULL,
  threshold_value DECIMAL(18,4) NOT NULL,
  window_seconds INT NOT NULL DEFAULT 300,
  duration_seconds INT NOT NULL DEFAULT 0,
  cooldown_seconds INT NOT NULL DEFAULT 900,
  level VARCHAR(16) NOT NULL DEFAULT 'warning',
  webhook_url VARCHAR(1024) DEFAULT NULL,
  notification_route_id BIGINT DEFAULT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_enabled (project_id, enabled),
  KEY idx_alert_rule_notification_route (notification_route_id),
  CONSTRAINT fk_monitor_alert_rule_notification_route FOREIGN KEY (notification_route_id)
    REFERENCES monitor_alert_notification_route (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';

INSERT INTO monitor_project (tenant_id, team_id, name, project_key, ingest_key_hash, release_key_hash, platform, status)
SELECT t.id, tm.id, 'Demo Web', 'demo-web', 'eef52729e3d17f2433c7f16c4fb6f0f0f03abffc31b8af6ed4bd55c75ca6a35e', 'f9de03afc6d38be6a8e8127ea2735762113c7383bb10ac242774564a164d11bc', 'web', 1
FROM monitor_tenant t
JOIN monitor_team tm ON tm.tenant_id = t.id AND tm.team_key = 'default'
WHERE t.tenant_key = 'default'
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1;

CREATE TABLE IF NOT EXISTS monitor_sourcemap (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  release_id BIGINT NOT NULL,
  bundle_file VARCHAR(512) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  checksum CHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_bundle (release_id, bundle_file),
  KEY idx_project_release (project_id, release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SourceMap 私有对象索引';

CREATE TABLE IF NOT EXISTS monitor_replay (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  release_version VARCHAR(128) DEFAULT NULL,
  object_key VARCHAR(1024) NOT NULL,
  event_count INT NOT NULL DEFAULT 0,
  start_time DATETIME DEFAULT NULL,
  end_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_replay_event (event_id),
  KEY idx_project_session (project_id, session_id),
  KEY idx_replay_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Session Replay 对象索引';

CREATE TABLE IF NOT EXISTS monitor_alert_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  project_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  metric VARCHAR(64) NOT NULL,
  metric_value DECIMAL(18,4) NOT NULL,
  threshold_value DECIMAL(18,4) NOT NULL,
  level VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'firing',
  fingerprint VARCHAR(128) DEFAULT NULL,
  message VARCHAR(512) NOT NULL,
  triggered_at DATETIME NOT NULL,
  recovered_at DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_triggered (project_id, triggered_at),
  KEY idx_rule_status (rule_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警触发记录';

CREATE TABLE IF NOT EXISTS monitor_alert_delivery (
  id VARCHAR(36) NOT NULL,
  project_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  alert_record_id BIGINT NOT NULL,
  alert_status VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL DEFAULT 0,
  claim_until BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  last_error VARCHAR(512) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_alert_delivery_project_created (project_id, created_at),
  KEY idx_alert_delivery_retry (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警通知投递持久记录';


-- ----------------------------
-- mall-tiny RBAC: 监控管理后台资源
-- ----------------------------
INSERT IGNORE INTO ums_resource_category (id, create_time, name, sort)
VALUES (100, NOW(), '监控模块', 100);

INSERT IGNORE INTO ums_resource (id, create_time, name, url, description, category_id)
VALUES (1000, NOW(), '监控管理后台', '/monitor/admin/**', 'Observability Admin API', 100);

INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT 5, 1000
WHERE NOT EXISTS (
  SELECT 1 FROM ums_role_resource_relation WHERE role_id = 5 AND resource_id = 1000
);

-- Existing monitor admins keep access to all current projects as owners.
INSERT IGNORE INTO monitor_project_member (project_id, admin_id, role)
SELECT p.id, a.id, 'OWNER'
FROM monitor_project p
JOIN ums_admin a ON a.status = 1
JOIN ums_admin_role_relation admin_role ON admin_role.admin_id = a.id
JOIN ums_role_resource_relation role_resource ON role_resource.role_id = admin_role.role_id
JOIN ums_resource resource ON resource.id = role_resource.resource_id
WHERE resource.url = '/monitor/admin/**';

UPDATE monitor_project p
JOIN (
  SELECT project_id, MIN(admin_id) AS admin_id
  FROM monitor_project_member
  WHERE role = 'OWNER'
  GROUP BY project_id
) owners ON owners.project_id = p.id
SET p.owner_id = owners.admin_id
WHERE p.owner_id IS NULL;

INSERT IGNORE INTO monitor_tenant_member (tenant_id, admin_id, role)
SELECT t.id, pm.admin_id,
  CASE
    WHEN MAX(CASE pm.role WHEN 'OWNER' THEN 3 WHEN 'MEMBER' THEN 2 ELSE 1 END) = 3 THEN 'OWNER'
    WHEN MAX(CASE pm.role WHEN 'OWNER' THEN 3 WHEN 'MEMBER' THEN 2 ELSE 1 END) = 2 THEN 'MEMBER'
    ELSE 'VIEWER'
  END
FROM monitor_project_member pm
JOIN monitor_project p ON p.id = pm.project_id
JOIN monitor_tenant t ON t.id = p.tenant_id
GROUP BY t.id, pm.admin_id;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_01', 'monitor project membership and project-scoped access');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_02', 'monitor replay index retention');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_03', 'monitor tenant team hierarchy');

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
  idempotency_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  item_value VARCHAR(2048) NOT NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_monitor_delete_item_key (job_id, item_type, idempotency_key),
  KEY idx_monitor_delete_item_batch (job_id, item_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控删除任务的跨存储幂等重建快照';

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_04', 'monitor data deletion jobs');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_05', 'monitor deletion item idempotency keys');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261002_06', 'monitor tenant audit log');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_01', 'monitor cron monitors and check-ins');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_03', 'monitor metric dimension cardinality governance');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_04', 'monitor uptime HTTP checks and history');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_05', 'monitor metric cardinality metric activity timestamp');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_06', 'monitor team memberships and team project access');

INSERT INTO ums_role (name, description, admin_count, create_time, status, sort)
SELECT 'Observability SCIM Member [monitor-scim-v1]', 'monitor-scim-system-role:v1; tenant/project scoped', 0, NOW(), 1, 90
WHERE NOT EXISTS (SELECT 1 FROM ums_role WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped');

SET @monitor_scim_member_role_id = (
  SELECT id FROM ums_role WHERE description = 'monitor-scim-system-role:v1; tenant/project scoped' ORDER BY id LIMIT 1
);
INSERT INTO ums_role_resource_relation (role_id, resource_id)
SELECT @monitor_scim_member_role_id, res.id
FROM ums_resource res
WHERE res.url = '/monitor/admin/**'
  AND NOT EXISTS (
    SELECT 1 FROM ums_role_resource_relation role_resource
    WHERE role_resource.role_id = @monitor_scim_member_role_id AND role_resource.resource_id = res.id
  )
LIMIT 1;

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_07', 'monitor SCIM credentials and provisioned identities');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_08', 'tenant SAML 2.0 service provider configuration');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_09', 'project-scoped saved Explore queries');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_10', 'tenant-shared alert notification routes');

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_11', 'tenant custom organizational roles');
