package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_tenant_audit_log")
public class MonitorTenantAuditLog implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long actorAdminId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String detailJson;
    private Date createTime;
}
