package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_uptime_check")
public class MonitorUptimeCheck implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String slug;
    private String name;
    private String url;
    private String method;
    private Integer intervalSeconds;
    private Integer timeoutMs;
    private Integer expectedStatusCode;
    private Integer failureThreshold;
    private Integer recoveryThreshold;
    private String status;
    private String currentStatus;
    private Integer consecutiveFailures;
    private Integer consecutiveSuccesses;
    private Date checkedAt;
    private Integer lastStatusCode;
    private Long lastDurationMs;
    private String lastError;
    private Date nextCheckAt;
    private Date createTime;
    private Date updateTime;
}
