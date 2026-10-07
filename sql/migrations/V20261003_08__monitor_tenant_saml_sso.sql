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

INSERT IGNORE INTO monitor_schema_migration (version, description)
VALUES ('20261003_08', 'tenant SAML 2.0 service provider configuration');
