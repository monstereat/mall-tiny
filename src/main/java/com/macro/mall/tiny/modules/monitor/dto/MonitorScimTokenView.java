package com.macro.mall.tiny.modules.monitor.dto;

import lombok.Data;

import java.util.Date;

@Data
public class MonitorScimTokenView {
    private Long id;
    private Long tenantId;
    private String name;
    private Date createTime;
    private Date lastUsedAt;
    private Date revokedAt;
}
