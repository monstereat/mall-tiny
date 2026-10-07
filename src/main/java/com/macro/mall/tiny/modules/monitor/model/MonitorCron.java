package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_cron")
public class MonitorCron implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String slug;
    private String name;
    private String scheduleType;
    private String schedule;
    private String timezone;
    private Integer checkinMarginSeconds;
    private Integer maxRuntimeSeconds;
    private Integer failureThreshold;
    private Integer recoveryThreshold;
    private String status;
    private String healthStatus;
    private Integer consecutiveFailures;
    private Integer consecutiveSuccesses;
    private Date lastCheckinAt;
    private String lastCheckinStatus;
    private Date nextCheckinAt;
    private Date createTime;
    private Date updateTime;
}
