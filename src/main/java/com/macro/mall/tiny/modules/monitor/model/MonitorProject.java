package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_project")
public class MonitorProject implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;
    private String projectKey;
    private String ingestKeyHash;
    private String platform;
    private Long ownerId;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
