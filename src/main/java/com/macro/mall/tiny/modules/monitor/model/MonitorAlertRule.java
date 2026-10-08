package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("monitor_alert_rule")
public class MonitorAlertRule implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    private String metric;
    private String operator;
    private BigDecimal thresholdValue;
    private Integer windowSeconds;
    private Integer durationSeconds;
    private Integer cooldownSeconds;
    private String level;
    private String webhookUrl;
    private Long notificationRouteId;
    private Integer enabled;
    private Date createTime;
    private Date updateTime;
}
