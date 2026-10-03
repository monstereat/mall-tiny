package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_scim_token")
public class MonitorScimToken implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String name;
    private String tokenHash;
    private Long createdBy;
    private Date createTime;
    private Date lastUsedAt;
    private Date revokedAt;
}
