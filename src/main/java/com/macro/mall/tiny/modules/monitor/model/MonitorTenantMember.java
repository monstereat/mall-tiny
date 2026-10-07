package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_tenant_member")
public class MonitorTenantMember implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long adminId;
    private String role;
    private Long customRoleId;
    private Date createTime;
    private Date updateTime;
}
