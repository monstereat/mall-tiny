package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_tenant_role")
public class MonitorTenantRole implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String roleKey;
    private String name;
    private String permissionsJson;
    private Date createTime;
    private Date updateTime;
}
