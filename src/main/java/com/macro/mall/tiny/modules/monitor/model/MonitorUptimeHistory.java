package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_uptime_history")
public class MonitorUptimeHistory implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long uptimeCheckId;
    private String status;
    private Integer responseStatus;
    private Long durationMs;
    private String message;
    private Date checkedAt;
    private Date createTime;
}
