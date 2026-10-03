package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_scim_user")
public class MonitorScimUser implements Serializable {

    @TableId(value = "scim_id", type = IdType.INPUT)
    private String scimId;
    private Long tenantId;
    private Long adminId;
    private String externalId;
    private Integer active;
    private Date createTime;
    private Date updateTime;
}
