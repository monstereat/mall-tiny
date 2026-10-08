package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("monitor_tenant_saml_config")
public class MonitorTenantSamlConfig {
    @TableId
    private Long tenantId;
    private String tenantKey;
    private Integer enabled;
    private String metadataXml;
    private String emailAttribute;
    private Date createTime;
    private Date updateTime;
}
