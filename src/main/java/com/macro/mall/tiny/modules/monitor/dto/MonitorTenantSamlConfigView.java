package com.macro.mall.tiny.modules.monitor.dto;

import lombok.Data;

import java.util.Date;

@Data
public class MonitorTenantSamlConfigView {
    private Long tenantId;
    private String tenantKey;
    private boolean enabled;
    private String metadataXml;
    private String emailAttribute;
    private Date updateTime;
    private String loginUrl;
    private String metadataUrl;
    private String logoutUrl;
}
