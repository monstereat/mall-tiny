package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("monitor_alert_record")
public class MonitorAlertRecord implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long ruleId;
    private String metric;
    private BigDecimal metricValue;
    private BigDecimal thresholdValue;
    private String level;
    private String status;
    private String fingerprint;
    private String message;
    private Date triggeredAt;
    private Date recoveredAt;
    private Date createTime;
}
