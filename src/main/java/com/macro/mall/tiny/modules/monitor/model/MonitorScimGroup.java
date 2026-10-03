package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_scim_group")
public class MonitorScimGroup implements Serializable {

    @TableId(value = "scim_id", type = IdType.INPUT)
    private String scimId;
    private Long tenantId;
    private Long teamId;
    private String externalId;
    private Date createTime;
    private Date updateTime;
}
